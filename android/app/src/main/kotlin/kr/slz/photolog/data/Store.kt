package kr.slz.photolog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.slz.photolog.core.Embeddings
import kr.slz.photolog.core.Events
import kr.slz.photolog.core.Grouping
import kr.slz.photolog.core.Tag
import kr.slz.photolog.core.TimeSource

/**
 * SQLite 저장소. `store.py`의 스키마와 쿼리를 그대로 옮겼다.
 *
 * **Room을 쓰지 않는다.** Room의 값은 컴파일 타임 SQL 검증과 마이그레이션인데, 이 스키마는
 * 데스크톱에서 이미 400장으로 검증됐고 쿼리도 그대로 온다. 대신 KSP 애노테이션 처리
 * 플러그인과 빌드 시간이 붙는다. 검증을 잃는 대가는 기기에서 도는 스모크 테스트로 메꾼다
 * (`StoreSmokeTest`). 마이그레이션이 실제로 여러 번 필요해지면 그때 Room으로 옮긴다.
 *
 * `state`는 데스크톱의 `indexed_at IS NULL` 패턴을 명시적 상태로 바꾼 것이다 —
 * 2단계 인덱싱(§5.1)에서 "메타는 됐고 CLIP은 안 됐다"를 표현해야 한다.
 */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "photolog.db", null, 1) {

    object State {
        const val NEW = 0        // MediaStore에서 막 발견
        const val META = 1       // Pass 1 완료 (EXIF·출처·규칙태그)
        const val DONE = 2       // Pass 2 완료 (임베딩·분류)
        const val FAILED = 3     // 열 수 없는 파일. 재시도하지 않는다
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE photos (
              media_id INTEGER PRIMARY KEY,
              uri TEXT NOT NULL,
              display_name TEXT,
              bucket TEXT, rel_path TEXT, owner_pkg TEXT,
              mime TEXT, bytes INTEGER,
              width INTEGER, height INTEGER, orientation INTEGER,
              generation INTEGER,
              date_modified INTEGER,
              taken_at INTEGER, taken_at_source TEXT,
              gps_lat REAL, gps_lon REAL, camera_model TEXT,
              blur_score REAL, brightness REAL,
              sha256 TEXT,
              embedding BLOB,
              model_id TEXT,
              state INTEGER NOT NULL DEFAULT 0
            )""")
        db.execSQL("CREATE INDEX ix_state ON photos(state)")
        db.execSQL("CREATE INDEX ix_taken ON photos(taken_at)")
        db.execSQL("CREATE INDEX ix_sha ON photos(sha256)")
        // 완전중복 후보를 좁히는 인덱스. 전부 해시하지 않기 위한 것(§12.1)
        db.execSQL("CREATE INDEX ix_dupe_candidate ON photos(bytes, width, height)")

        db.execSQL("""
            CREATE TABLE tags (
              media_id INTEGER NOT NULL,
              label TEXT NOT NULL,
              score REAL,
              source TEXT NOT NULL,
              PRIMARY KEY (media_id, label)
            )""")
        db.execSQL("CREATE INDEX ix_tag_label ON tags(label)")

        db.execSQL("""
            CREATE TABLE events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              started_at INTEGER, ended_at INTEGER,
              gps_lat REAL, gps_lon REAL,
              place_name TEXT, title TEXT
            )""")
        db.execSQL("""
            CREATE TABLE event_photos (
              event_id INTEGER NOT NULL,
              media_id INTEGER NOT NULL,
              is_cover INTEGER DEFAULT 0,
              PRIMARY KEY (event_id, media_id)
            )""")
        db.execSQL("""
            CREATE TABLE dupe_groups (
              group_id INTEGER NOT NULL,
              media_id INTEGER NOT NULL,
              kind TEXT NOT NULL,
              is_best INTEGER DEFAULT 0,
              PRIMARY KEY (group_id, media_id)
            )""")
        // 사용자 교정. 개인화(§8.5)의 입력이고, 백업에서 유일하게 살릴 가치가 있는 표다
        db.execSQL("""
            CREATE TABLE user_feedback (
              media_id INTEGER NOT NULL,
              label TEXT NOT NULL,
              verdict INTEGER NOT NULL,
              ts INTEGER NOT NULL,
              PRIMARY KEY (media_id, label)
            )""")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 파생 데이터라 재생성이 안전하다. 단 user_feedback은 사용자가 직접 넣은
        // 정보라 살린다 — 그것까지 날리면 교정을 다시 하라는 뜻이 된다.
        val saved = mutableListOf<ContentValues>()
        runCatching {
            db.rawQuery("SELECT media_id, label, verdict, ts FROM user_feedback", null).use { c ->
                while (c.moveToNext()) saved.add(ContentValues().apply {
                    put("media_id", c.getLong(0)); put("label", c.getString(1))
                    put("verdict", c.getInt(2)); put("ts", c.getLong(3))
                })
            }
        }
        for (t in listOf("photos", "tags", "events", "event_photos", "dupe_groups", "user_feedback", "meta"))
            db.execSQL("DROP TABLE IF EXISTS $t")
        onCreate(db)
        for (v in saved) db.insertWithOnConflict("user_feedback", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL: 워커가 쓰는 동안 UI가 읽어도 서로 막지 않는다.
        db.enableWriteAheadLogging()
    }

    // ---------- meta ----------

    fun metaGet(k: String): String? = readableDatabase
        .rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToNext()) it.getString(0) else null }

    fun metaPut(k: String, v: String) {
        writableDatabase.insertWithOnConflict("meta", null,
            ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 모델이 바뀌면 임베딩은 못 쓴다 — 다른 공간의 벡터라 유사도가 무의미해진다.
     * Pass 1 결과(EXIF·출처·규칙태그)는 모델과 무관하므로 남기고 Pass 2만 다시 돈다.
     * @return 무효화된 장수
     */
    fun checkModel(modelId: String): Int {
        if (metaGet("model_id") == modelId) return 0
        val db = writableDatabase
        val n = db.rawQuery("SELECT COUNT(*) FROM photos WHERE embedding IS NOT NULL", null)
            .use { if (it.moveToNext()) it.getInt(0) else 0 }
        if (n > 0) {
            db.execSQL("UPDATE photos SET embedding=NULL, state=? WHERE state=?",
                arrayOf(State.META, State.DONE))
            db.execSQL("DELETE FROM tags WHERE source='clip'")
        }
        metaPut("model_id", modelId)
        return n
    }

    // ---------- MediaStore 동기화 ----------

    /** 새 경로만 큐잉. media_id가 PK라 재스캔해도 중복되지 않는다. */
    fun upsertDiscovered(rows: List<MediaRow>): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("media_id", r.id); put("uri", r.uri); put("display_name", r.displayName)
                    put("bucket", r.bucket); put("rel_path", r.relativePath); put("owner_pkg", r.ownerPackage)
                    put("mime", r.mime); put("bytes", r.size)
                    put("width", r.width); put("height", r.height); put("orientation", r.orientation)
                    put("generation", r.generation); put("date_modified", r.dateModified)
                }
                // 이미 있으면 메타만 갱신하고 state는 건드리지 않는다 — 사진이 실제로
                // 바뀌었으면 generation이 오르므로 아래에서 되돌린다.
                val updated = db.update("photos", v, "media_id=?", arrayOf(r.id.toString()))
                if (updated == 0) {
                    v.put("state", State.NEW)
                    db.insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

    /** 편집된 사진은 처음부터 다시. generation이 올랐다는 것은 내용이 바뀌었다는 뜻이다. */
    fun invalidateChanged(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val q = ids.joinToString(",")
        db.execSQL("UPDATE photos SET state=${State.NEW}, embedding=NULL, sha256=NULL WHERE media_id IN ($q)")
        db.execSQL("DELETE FROM tags WHERE media_id IN ($q)")
    }

    /** MediaStore에서 사라진 사진을 지운다(휴지통 포함). */
    fun deleteMissing(present: Set<Long>): Int {
        val db = writableDatabase
        val gone = ArrayList<Long>()
        db.rawQuery("SELECT media_id FROM photos", null).use { c ->
            while (c.moveToNext()) c.getLong(0).let { if (it !in present) gone.add(it) }
        }
        if (gone.isEmpty()) return 0
        db.beginTransaction()
        try {
            for (chunk in gone.chunked(500)) {
                val q = chunk.joinToString(",")
                db.execSQL("DELETE FROM photos WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM tags WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM event_photos WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM dupe_groups WHERE media_id IN ($q)")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return gone.size
    }

    // ---------- 파이프라인 ----------

    fun pending(state: Int, limit: Int): List<Pending> =
        readableDatabase.rawQuery(
            "SELECT media_id, uri, display_name, rel_path, owner_pkg, mime, bytes, width, height," +
                " date_modified FROM photos WHERE state=? ORDER BY media_id LIMIT ?",
            arrayOf(state.toString(), limit.toString())
        ).use { c ->
            ArrayList<Pending>(c.count).apply {
                while (c.moveToNext()) add(Pending(
                    id = c.getLong(0), uri = c.getString(1), displayName = c.str(2),
                    relativePath = c.str(3), ownerPackage = c.str(4), mime = c.str(5),
                    bytes = c.getLong(6), width = c.getInt(7), height = c.getInt(8),
                    dateModified = c.longOrNull(9) ?: 0L,
                ))
            }
        }

    fun counts(): Counts = readableDatabase.rawQuery(
        "SELECT COUNT(*), SUM(state>=${State.META}), SUM(state=${State.DONE}), SUM(state=${State.FAILED})" +
            " FROM photos", null
    ).use {
        if (it.moveToNext()) Counts(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3))
        else Counts(0, 0, 0, 0)
    }

    /** Pass 1 결과. 규칙 태그는 source='rule'로 들어간다. */
    fun saveMeta(id: Long, m: MetaResult, tags: List<Tag>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("photos", ContentValues().apply {
                put("taken_at", m.takenAt); put("taken_at_source", m.takenAtSource.name.lowercase())
                put("gps_lat", m.lat); put("gps_lon", m.lon); put("camera_model", m.cameraModel)
                put("state", State.META)
            }, "media_id=?", arrayOf(id.toString()))
            replaceTags(db, id, tags, "rule")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Pass 2 결과. CLIP 태그와 임베딩. */
    fun saveClip(id: Long, embedding: FloatArray, blur: Double, brightness: Double, tags: List<Tag>, modelId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("photos", ContentValues().apply {
                put("embedding", Embeddings.encode(embedding))
                put("blur_score", blur); put("brightness", brightness)
                put("model_id", modelId); put("state", State.DONE)
            }, "media_id=?", arrayOf(id.toString()))
            replaceTags(db, id, tags, "clip")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun markFailed(id: Long) {
        writableDatabase.execSQL("UPDATE photos SET state=${State.FAILED} WHERE media_id=?", arrayOf(id))
    }

    fun setSha(id: Long, sha: String) {
        writableDatabase.execSQL("UPDATE photos SET sha256=? WHERE media_id=?", arrayOf(sha, id))
    }

    private fun replaceTags(db: SQLiteDatabase, id: Long, tags: List<Tag>, source: String) {
        db.delete("tags", "media_id=? AND source=?", arrayOf(id.toString(), source))
        for (t in tags) db.insertWithOnConflict("tags", null, ContentValues().apply {
            put("media_id", id); put("label", t.label); put("score", t.score?.toDouble()); put("source", t.source)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- 조회 ----------

    /** 태그별 장수. 앨범 목록의 원본이다. */
    fun tagCounts(): List<TagCount> = readableDatabase.rawQuery(
        "SELECT t.label, t.source, COUNT(*) n FROM tags t JOIN photos p ON p.media_id=t.media_id" +
            " GROUP BY t.label ORDER BY n DESC", null
    ).use { c ->
        ArrayList<TagCount>(c.count).apply {
            while (c.moveToNext()) add(TagCount(c.getString(0), c.str(1) ?: "clip", c.getInt(2)))
        }
    }

    /** 태그가 붙은 사진. 최신순. */
    fun photosByTag(label: String, limit: Int = 500, offset: Int = 0): List<PhotoCard> =
        readableDatabase.rawQuery(
            "SELECT p.media_id, p.uri, p.taken_at, p.taken_at_source FROM photos p" +
                " JOIN tags t ON t.media_id=p.media_id WHERE t.label=? AND p.state>=${State.META}" +
                " ORDER BY p.taken_at IS NULL, p.taken_at DESC LIMIT ? OFFSET ?",
            arrayOf(label, limit.toString(), offset.toString())
        ).use { it.toCards() }

    fun photosByIds(ids: List<Long>): Map<Long, PhotoCard> {
        if (ids.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            "SELECT media_id, uri, taken_at, taken_at_source FROM photos" +
                " WHERE media_id IN (${ids.joinToString(",")})", null
        ).use { it.toCards() }.associateBy { it.id }
    }

    fun recent(limit: Int): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT media_id, uri, taken_at, taken_at_source FROM photos WHERE state>=${State.META}" +
            " ORDER BY taken_at IS NULL, taken_at DESC LIMIT ?", arrayOf(limit.toString())
    ).use { it.toCards() }

    fun photo(id: Long): PhotoDetail? = readableDatabase.rawQuery(
        "SELECT media_id, uri, display_name, taken_at, taken_at_source, gps_lat, gps_lon," +
            " camera_model, width, height, bytes, blur_score, brightness, mime, rel_path, owner_pkg" +
            " FROM photos WHERE media_id=?", arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToNext()) return null
        PhotoDetail(
            id = c.getLong(0), uri = c.getString(1), displayName = c.str(2),
            takenAt = c.longOrNull(3), takenAtSource = TimeSource.parse(c.str(4)),
            lat = c.dblOrNull(5), lon = c.dblOrNull(6), cameraModel = c.str(7),
            width = c.getInt(8), height = c.getInt(9), bytes = c.getLong(10),
            blurScore = c.dblOrNull(11), brightness = c.dblOrNull(12),
            mime = c.str(13), relativePath = c.str(14), ownerPackage = c.str(15),
            tags = tagsOf(id),
        )
    }

    fun tagsOf(id: Long): List<Tag> = readableDatabase.rawQuery(
        "SELECT label, score, source FROM tags WHERE media_id=? ORDER BY source, score DESC",
        arrayOf(id.toString())
    ).use { c ->
        ArrayList<Tag>(c.count).apply {
            while (c.moveToNext()) add(Tag(c.getString(0), if (c.isNull(1)) null else c.getFloat(1), c.getString(2)))
        }
    }

    /** 검색 인덱스를 채울 임베딩 전량. 시작할 때 한 번 읽는다(§10.1). */
    fun allEmbeddings(): List<Pair<Long, FloatArray>> = readableDatabase.rawQuery(
        "SELECT media_id, embedding FROM photos WHERE embedding IS NOT NULL", null
    ).use { c ->
        ArrayList<Pair<Long, FloatArray>>(c.count).apply {
            while (c.moveToNext()) add(c.getLong(0) to Embeddings.decode(c.getBlob(1))[0])
        }
    }

    /** 그룹핑 입력. 임베딩까지 싣는다. */
    fun groupingItems(): List<Grouping.Item> = readableDatabase.rawQuery(
        "SELECT media_id, display_name, sha256, taken_at, taken_at_source, blur_score, embedding" +
            " FROM photos WHERE state>=${State.META}", null
    ).use { c ->
        ArrayList<Grouping.Item>(c.count).apply {
            while (c.moveToNext()) add(Grouping.Item(
                id = c.getLong(0), displayName = c.str(1) ?: "",
                sha256 = c.str(2), takenAt = c.longOrNull(3),
                takenAtSource = TimeSource.parse(c.str(4)),
                blurScore = c.dblOrNull(5),
                embedding = if (c.isNull(6)) null else Embeddings.decode(c.getBlob(6))[0],
            ))
        }
    }

    /** 이벤트 입력. 태그까지 붙여야 제목을 만들 수 있다. */
    fun eventItems(): List<Events.Photo> {
        val tagsByPhoto = HashMap<Long, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT media_id, label FROM tags WHERE source='clip'", null
        ).use { c -> while (c.moveToNext()) tagsByPhoto.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1)) }

        return readableDatabase.rawQuery(
            "SELECT media_id, taken_at, taken_at_source, gps_lat, gps_lon, blur_score, embedding" +
                " FROM photos WHERE taken_at IS NOT NULL AND state>=${State.META}", null
        ).use { c ->
            ArrayList<Events.Photo>(c.count).apply {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(Events.Photo(
                        id = id, takenAt = c.getLong(1), takenAtSource = TimeSource.parse(c.str(2)),
                        lat = c.dblOrNull(3), lon = c.dblOrNull(4), blurScore = c.dblOrNull(5),
                        embedding = if (c.isNull(6)) null else Embeddings.decode(c.getBlob(6))[0],
                        tags = tagsByPhoto[id].orEmpty(),
                    ))
                }
            }
        }
    }

    // ---------- 이벤트 / 중복 저장 ----------

    fun replaceEvents(events: List<Events.Event>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM event_photos")
            db.execSQL("DELETE FROM events")
            for (e in events) {
                val eid = db.insert("events", null, ContentValues().apply {
                    put("started_at", e.startedAt); put("ended_at", e.endedAt)
                    put("gps_lat", e.lat); put("gps_lon", e.lon)
                    put("place_name", e.placeName); put("title", e.title)
                })
                for (pid in e.photoIds) db.insertWithOnConflict("event_photos", null,
                    ContentValues().apply {
                        put("event_id", eid); put("media_id", pid)
                        put("is_cover", if (pid in e.coverIds) 1 else 0)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun replaceDupes(groups: List<Grouping.Group>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM dupe_groups")
            groups.forEachIndexed { i, g ->
                for (pid in g.ids) db.insertWithOnConflict("dupe_groups", null,
                    ContentValues().apply {
                        put("group_id", i + 1); put("media_id", pid)
                        put("kind", g.kind.name.lowercase())
                        put("is_best", if (pid == g.bestId) 1 else 0)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun events(limit: Int = 100): List<EventRow> = readableDatabase.rawQuery(
        "SELECT e.id, e.started_at, e.ended_at, e.place_name, e.title," +
            " (SELECT COUNT(*) FROM event_photos WHERE event_id=e.id) n" +
            " FROM events e ORDER BY e.started_at DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c ->
        ArrayList<EventRow>(c.count).apply {
            while (c.moveToNext()) add(EventRow(
                id = c.getLong(0), startedAt = c.getLong(1), endedAt = c.getLong(2),
                placeName = c.str(3), title = c.str(4) ?: "", count = c.getInt(5),
                coverIds = covers(c.getLong(0)),
            ))
        }
    }

    private fun covers(eventId: Long): List<Long> = readableDatabase.rawQuery(
        "SELECT media_id FROM event_photos WHERE event_id=? AND is_cover=1 LIMIT 3",
        arrayOf(eventId.toString())
    ).use { c -> ArrayList<Long>(3).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    fun eventPhotos(eventId: Long): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT p.media_id, p.uri, p.taken_at, p.taken_at_source FROM photos p" +
            " JOIN event_photos ep ON ep.media_id=p.media_id WHERE ep.event_id=?" +
            " ORDER BY p.taken_at", arrayOf(eventId.toString())
    ).use { it.toCards() }

    fun dupeGroups(): List<Grouping.Group> {
        val byGroup = LinkedHashMap<Int, Triple<Grouping.Kind, MutableList<Long>, Long>>()
        readableDatabase.rawQuery(
            "SELECT group_id, media_id, kind, is_best FROM dupe_groups ORDER BY group_id", null
        ).use { c ->
            while (c.moveToNext()) {
                val gid = c.getInt(0); val pid = c.getLong(1)
                val kind = if (c.getString(2) == "exact") Grouping.Kind.EXACT else Grouping.Kind.BURST
                val e = byGroup.getOrPut(gid) { Triple(kind, ArrayList(), 0L) }
                e.second.add(pid)
                if (c.getInt(3) == 1) byGroup[gid] = Triple(e.first, e.second, pid)
            }
        }
        return byGroup.values.map { Grouping.Group(it.first, it.second, if (it.third != 0L) it.third else it.second.first()) }
    }

    /** 정리 후보: 흐린 사진. 임계값은 사용자가 분포를 보고 정한다(§12.4). */
    fun blurry(threshold: Double, limit: Int = 500): List<PhotoCard> =
        if (threshold <= 0) emptyList() else readableDatabase.rawQuery(
            "SELECT media_id, uri, taken_at, taken_at_source FROM photos" +
                " WHERE blur_score IS NOT NULL AND blur_score < ? ORDER BY blur_score LIMIT ?",
            arrayOf(threshold.toString(), limit.toString())
        ).use { it.toCards() }

    /** 흐림 임계값은 카메라마다 달라 기본값을 박을 수 없다. 실제 분포를 보여 준다. */
    fun blurPercentiles(): Map<Int, Double> {
        val v = readableDatabase.rawQuery(
            "SELECT blur_score FROM photos WHERE blur_score IS NOT NULL ORDER BY blur_score", null
        ).use { c -> DoubleArray(c.count).also { a -> var i = 0; while (c.moveToNext()) a[i++] = c.getDouble(0) } }
        if (v.isEmpty()) return emptyMap()
        return listOf(1, 5, 10, 25, 50, 90).associateWith { p ->
            v[((v.size - 1) * p / 100.0).toInt()]
        }
    }

    fun feedback(id: Long, label: String, correct: Boolean) {
        writableDatabase.insertWithOnConflict("user_feedback", null, ContentValues().apply {
            put("media_id", id); put("label", label)
            put("verdict", if (correct) 1 else 0); put("ts", System.currentTimeMillis() / 1000)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 사용자가 확정한 사진의 임베딩. 개인화 프로토타입의 재료(§8.5). */
    fun confirmedEmbeddings(label: String): List<FloatArray> = readableDatabase.rawQuery(
        "SELECT p.embedding FROM photos p JOIN user_feedback f ON f.media_id=p.media_id" +
            " WHERE f.label=? AND f.verdict=1 AND p.embedding IS NOT NULL", arrayOf(label)
    ).use { c ->
        ArrayList<FloatArray>(c.count).apply {
            while (c.moveToNext()) add(Embeddings.decode(c.getBlob(0))[0])
        }
    }

    /** 태그를 사람이 옮긴다. clip 태그를 지우고 user 태그를 넣는다 — 출처가 남아야 한다. */
    fun moveTag(ids: List<Long>, from: String?, to: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val q = ids.joinToString(",")
        db.beginTransaction()
        try {
            if (from != null) db.execSQL("DELETE FROM tags WHERE media_id IN ($q) AND label=?", arrayOf(from))
            for (id in ids) {
                db.insertWithOnConflict("tags", null, ContentValues().apply {
                    put("media_id", id); put("label", to); put("source", "user")
                }, SQLiteDatabase.CONFLICT_REPLACE)
                feedback(id, to, true)
                if (from != null) feedback(id, from, false)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearAnalysis() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM tags"); db.execSQL("DELETE FROM events")
            db.execSQL("DELETE FROM event_photos"); db.execSQL("DELETE FROM dupe_groups")
            db.execSQL("UPDATE photos SET state=${State.NEW}, embedding=NULL, sha256=NULL," +
                " blur_score=NULL, brightness=NULL")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 전체 다시 분석 — Pass 2만. 메타는 그대로 두면 로그가 안 사라진다. */
    fun requeueClip() {
        writableDatabase.execSQL(
            "UPDATE photos SET state=${State.META}, embedding=NULL WHERE state=${State.DONE}")
        writableDatabase.execSQL("DELETE FROM tags WHERE source='clip'")
    }
}

// ---------- 커서 헬퍼 ----------

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
private fun Cursor.dblOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

private fun Cursor.toCards(): List<PhotoCard> = ArrayList<PhotoCard>(count).also { out ->
    while (moveToNext()) out.add(PhotoCard(
        id = getLong(0), uri = getString(1),
        takenAt = longOrNull(2), takenAtSource = TimeSource.parse(str(3)),
    ))
}
