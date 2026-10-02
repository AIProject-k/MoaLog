package com.moalog.personal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.Events
import com.moalog.personal.core.Grouping
import com.moalog.personal.core.Tag
import com.moalog.personal.core.TimeSource
import com.moalog.personal.core.VideoSampling

internal const val GROUPING_COMPLETE = "grouping_complete"
internal const val KEY_VIDEO_CAPTURE_BACKFILL = "video_capture_backfill"
internal const val KEY_VIDEO_SAMPLING = "video_sampling"
internal const val KEY_VIDEO_TAGS = "video_tags"

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
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "photolog.db", null, 8) {

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
              media_kind TEXT NOT NULL DEFAULT 'photo', duration_ms INTEGER,
              is_accessible INTEGER NOT NULL DEFAULT 0,
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
        createUserTables(db)
        createVideoTables(db)
    }

    /**
     * 사용자가 직접 만든 것들. 파생 데이터가 아니라 **입력**이므로 스키마가 올라가도
     * 지우지 않는다. `IF NOT EXISTS`인 이유는 [onUpgrade]가 같은 함수를 부르기 때문이다 —
     * 표를 두 곳에 적으면 한쪽만 고치는 사고가 난다.
     */
    private fun createUserTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (media_id INTEGER PRIMARY KEY, ts INTEGER NOT NULL)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_albums (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )""")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_album_photos (
              album_id INTEGER NOT NULL,
              media_id INTEGER NOT NULL,
              added_at INTEGER NOT NULL,
              scene_time_ms INTEGER,
              PRIMARY KEY (album_id, media_id)
            )""")
        // 중복 묶음에 대한 사용자의 결정. `keep_id`는 '보관 추천'을 사람이 바꾼 것이고
        // `dismissed`는 "이 묶음은 유지" — 둘 다 재분석해도 살아남아야 한다.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dupe_choice (
              group_key TEXT PRIMARY KEY,
              keep_id INTEGER,
              dismissed INTEGER NOT NULL DEFAULT 0
            )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 -> v2 는 **표를 더하기만 한다**. photos 스키마가 그대로이므로 임베딩을
        // 버릴 이유가 없다 — 버리면 1만 장을 다시 분석해야 하고, 그게 몇 시간이다.
        if (old < 2) createUserTables(db)
        if (old < 3) createVideoTables(db)
        if (old < 4) {
            if (!hasColumn(db, "photos", "media_kind"))
                db.execSQL("ALTER TABLE photos ADD COLUMN media_kind TEXT NOT NULL DEFAULT 'photo'")
            if (!hasColumn(db, "photos", "duration_ms"))
                db.execSQL("ALTER TABLE photos ADD COLUMN duration_ms INTEGER")
            if (!hasColumn(db, "user_album_photos", "scene_time_ms"))
                db.execSQL("ALTER TABLE user_album_photos ADD COLUMN scene_time_ms INTEGER")
            db.execSQL("UPDATE photos SET media_kind='video' WHERE media_id<0")
            metaPut(db, "video_duration_backfill", "1")
        }
        // Earlier workers rejected videos with no duration. Retry those derived rows once; the
        // current worker can still index a frame at t=0 without duration metadata.
        if (old < 6) db.execSQL(
            "UPDATE photos SET state=${State.META} WHERE media_id<0 AND state=${State.FAILED}"
        )
        // v7 이전 행은 전부 보이던 것들이라 **보이는 상태로** 들인다. 0으로 두면 업데이트 직후
        // 첫 동기화가 끝날 때까지 갤러리가 텅 비어 보인다. 권한이 줄었다면 동기화가 다시 숨긴다.
        // (v7 DB는 동기화가 이미 맞게 채워 뒀으므로 손대지 않는다 — 일부 허용에서 뺀 항목이
        // 잠깐이라도 다시 보이면 안 된다.)
        if (old < 7 && !hasColumn(db, "photos", "is_accessible"))
            db.execSQL("ALTER TABLE photos ADD COLUMN is_accessible INTEGER NOT NULL DEFAULT 1")
        // 예전 영상은 촬영 시각을 읽지 않고 파일 시각으로 저장했다. 분석은 그대로 두고
        // 시각·위치만 다시 읽도록 표시한다(IndexWorker.backfillVideoCapture).
        if (old < 8) metaPut(db, KEY_VIDEO_CAPTURE_BACKFILL, "1")
        if (old > new) rebuild(db)          // 다운그레이드는 되돌릴 방법이 없다
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) if (c.getString(name) == column) return@use true
            false
        }

    /**
     * 최후의 수단. 파생 데이터라 재생성이 안전하지만 사용자가 넣은 정보(교정·즐겨찾기·
     * 내 앨범·중복 결정)는 살린다 — 그것까지 날리면 처음부터 다시 하라는 뜻이 된다.
     */
    private fun rebuild(db: SQLiteDatabase) {
        val saved = HashMap<String, MutableList<ContentValues>>()
        for ((table, cols) in KEEP_TABLES) runCatching {
            db.rawQuery("SELECT ${cols.joinToString(",")} FROM $table", null).use { c ->
                val rows = saved.getOrPut(table) { ArrayList() }
                while (c.moveToNext()) rows.add(ContentValues().apply {
                    cols.forEachIndexed { i, name -> if (!c.isNull(i)) put(name, c.getString(i)) }
                })
            }
        }
        for (t in ALL_TABLES) db.execSQL("DROP TABLE IF EXISTS $t")
        onCreate(db)
        for ((table, rows) in saved) for (v in rows)
            db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL: 워커가 쓰는 동안 UI가 읽어도 서로 막지 않는다.
        db.enableWriteAheadLogging()
    }

    // ---------- meta ----------

    private fun createVideoTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS video_frames (media_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, embedding BLOB NOT NULL, PRIMARY KEY(media_id,time_ms))")
    }

    fun durationMs(id: Long): Long? = readableDatabase.rawQuery(
        "SELECT duration_ms FROM photos WHERE media_id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.longOrNull(0) else null }

    fun generations(): Map<Long, Long> = readableDatabase.rawQuery("SELECT media_id,generation FROM photos", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
    }

    fun generations(video: Boolean): Map<Long, Long> = readableDatabase.rawQuery(
        "SELECT media_id,generation FROM photos WHERE media_id${if (video) "<" else ">"}0", null
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) } }

    fun accessibleIds(): Set<Long> = readableDatabase.rawQuery(
        "SELECT media_id FROM photos WHERE is_accessible=1", null
    ).use { c -> HashSet<Long>(c.count).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    /** Partial access hides rows without deleting their indexes or user album links. */
    fun setAccessible(ids: Set<Long>, video: Boolean): Boolean {
        val db = writableDatabase
        val kindWhere = if (video) "media_id<0" else "media_id>0"
        val currentlyAccessible = db.rawQuery(
            "SELECT media_id FROM photos WHERE $kindWhere AND is_accessible=1", null
        ).use { c -> HashSet<Long>(c.count).apply { while (c.moveToNext()) add(c.getLong(0)) } }
        val hidden = currentlyAccessible.filterNot(ids::contains)
        val shown = ids.filterNot(currentlyAccessible::contains)
        var changed = 0
        db.beginTransaction()
        try {
            for (chunk in hidden.chunked(500)) {
                val q = chunk.joinToString(",")
                changed += db.update("photos", ContentValues().apply { put("is_accessible", 0) },
                    "$kindWhere AND is_accessible=1 AND media_id IN ($q)", null)
            }
            for (chunk in shown.chunked(500)) {
                val q = chunk.joinToString(",")
                changed += db.update("photos", ContentValues().apply { put("is_accessible", 1) },
                    "$kindWhere AND is_accessible=0 AND media_id IN ($q)", null)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return changed > 0
    }

    fun saveFrame(id: Long, timeMs: Long, vector: FloatArray) {
        writableDatabase.insertWithOnConflict("video_frames", null, ContentValues().apply {
            put("media_id", id); put("time_ms", timeMs); put("embedding", Embeddings.encode(vector))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun frames(id: Long): Map<Long, FloatArray> = readableDatabase.rawQuery(
        "SELECT time_ms,embedding FROM video_frames WHERE media_id=? ORDER BY time_ms", arrayOf(id.toString())
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), Embeddings.decode(c.getBlob(1))[0]) } }

    /** 영상마다 가장 잘 맞은 장면 하나. 점수는 코사인 유사도이지 확률이 아니다. */
    fun searchVideoFrames(query: FloatArray, allow: Set<Long>?): Map<Long, Pair<Long, Float>> =
        searchVideoScenes(query, allow, perVideo = 1).mapValues { it.value.first() }

    /**
     * 영상마다 잘 맞은 장면 여러 개(점수 순, 서로 떨어진 것만). 영상 **파일**이 아니라
     * **장면**을 찾는 게 목적이다 — "02:18에 있다"를 알려 줘야 긴 영상에서 쓸모가 있다.
     */
    fun searchVideoScenes(query: FloatArray, allow: Set<Long>?, perVideo: Int = 3): Map<Long, List<Pair<Long, Float>>> =
        readableDatabase.rawQuery("SELECT f.media_id,f.time_ms,f.embedding FROM video_frames f JOIN photos p ON p.media_id=f.media_id WHERE p.state<>${State.FAILED} AND p.is_accessible=1", null).use { c ->
            val hits = HashMap<Long, MutableList<Pair<Long, Float>>>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (allow != null && id !in allow) continue
                val score = Embeddings.dot(query, Embeddings.decode(c.getBlob(2))[0])
                hits.getOrPut(id) { ArrayList() }.add(c.getLong(1) to score)
            }
            hits.mapValues { (_, h) -> VideoSampling.topScenes(h, perVideo) }
        }

    /**
     * 프레임 샘플링 방식이 바뀌었을 때 예전 방식으로 분석된 영상을 다시 대기열에 올린다.
     *
     * **예전 프레임은 지운다.** 워커는 같은 시각의 프레임이 있으면 캐시로 재사용하는데, 방식이
     * 바뀐 뒤의 재사용은 예전 방식으로 뽑은 프레임을 새 결과로 섞게 된다(간격이 같고 뽑는 방식만
     * 바뀌면 전부 재사용돼 아무것도 다시 계산되지 않는다). 대신 태그는 새 결과로 덮어쓸 때까지
     * 두어 AI 앨범이 비지 않는다. 사진과 사용자가 만든 것은 건드리지 않는다.
     * 열 수 없어서 실패로 끝난 영상도 이번에 한 번 더 시도한다.
     * @return 다시 분석할 영상 수
     */
    fun requeueVideos(): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM video_frames")          // 프레임은 영상 것뿐이다
            n += db.compileStatement(
                "UPDATE photos SET state=${State.META} WHERE media_id<0 AND state=${State.DONE}"
            ).use { it.executeUpdateDelete() }
            n += db.compileStatement(
                "UPDATE photos SET state=${State.NEW} WHERE media_id<0 AND state=${State.FAILED}"
            ).use { it.executeUpdateDelete() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    /** 프레임이 저장된 영상. 태그를 저장된 프레임 벡터로 다시 만들 때 쓴다. */
    fun videoIdsWithFrames(): List<Long> = readableDatabase.rawQuery(
        "SELECT DISTINCT f.media_id FROM video_frames f JOIN photos p ON p.media_id=f.media_id" +
            " WHERE p.state=${State.DONE} ORDER BY f.media_id", null
    ).use { c -> ArrayList<Long>(c.count).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    /**
     * AI 분류 태그(source='clip')만 통째로 바꾼다. 규칙 태그와 사용자가 고정한 태그, 상태·임베딩·
     * 프레임은 건드리지 않는다.
     */
    fun setClipTags(id: Long, tags: List<Tag>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            replaceTags(db, id, tags, "clip")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 촬영 시각·위치만 고친다. 분석 상태(state)와 임베딩은 건드리지 않는다. */
    fun updateCapture(id: Long, m: MetaResult) {
        writableDatabase.update("photos", ContentValues().apply {
            put("taken_at", m.takenAt); put("taken_at_source", m.takenAtSource.name.lowercase())
            put("gps_lat", m.lat); put("gps_lon", m.lon)
        }, "media_id=?", arrayOf(id.toString()))
    }

    /** 촬영 시각을 컨테이너에서 다시 읽어야 하는 영상. */
    fun videosWithFileTime(): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT media_id, uri FROM photos WHERE media_id<0 AND state>=${State.META}" +
            " AND state<>${State.FAILED} AND (taken_at_source IS NULL OR taken_at_source<>'exif')", null
    ).use { c -> ArrayList<Pair<Long, String>>(c.count).apply { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }

    fun metaGet(k: String): String? = readableDatabase
        .rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToNext()) it.getString(0) else null }

    fun metaPut(k: String, v: String) {
        writableDatabase.insertWithOnConflict("meta", null,
            ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun metaPut(db: SQLiteDatabase, k: String, v: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("k", k); put("v", v)
        }, SQLiteDatabase.CONFLICT_REPLACE)
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
            db.execSQL("DELETE FROM video_frames")
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
                    put("media_kind", r.mediaKind.value); put("duration_ms", r.durationMs)
                    put("is_accessible", 1)
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
        db.execSQL("DELETE FROM video_frames WHERE media_id IN ($q)")
        db.execSQL("DELETE FROM tags WHERE media_id IN ($q)")
    }

    /** 선택한 매체에서만 사라진 항목을 지운다. 다른 매체의 동기화 결과는 건드리지 않는다. */
    fun deleteMissing(present: Set<Long>, video: Boolean): Int {
        val db = writableDatabase
        val gone = ArrayList<Long>()
        db.rawQuery("SELECT media_id FROM photos WHERE media_id${if (video) "<" else ">"}0", null).use { c ->
            while (c.moveToNext()) c.getLong(0).let { if (it !in present) gone.add(it) }
        }
        if (gone.isEmpty()) return 0
        db.beginTransaction()
        try {
            for (chunk in gone.chunked(500)) {
                val q = chunk.joinToString(",")
                db.execSQL("DELETE FROM photos WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM video_frames WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM tags WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM event_photos WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM dupe_groups WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM favorites WHERE media_id IN ($q)")
                db.execSQL("DELETE FROM user_album_photos WHERE media_id IN ($q)")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return gone.size
    }

    // ---------- 파이프라인 ----------

    fun pendingPhotos(state: Int, limit: Int): List<Pending> = pending(state, limit, "media_id>0")
    fun pendingVideos(state: Int, limit: Int): List<Pending> = pending(state, limit, "media_id<0")
    fun countsPhotos(): Counts = counts("media_id>0")
    fun countsVideos(): Counts = counts("media_id<0")

    private fun pending(state: Int, limit: Int, kindWhere: String): List<Pending> =
        readableDatabase.rawQuery(
            "SELECT media_id, uri, display_name, rel_path, owner_pkg, mime, bytes, width, height," +
                " date_modified, media_kind, duration_ms FROM photos WHERE state=? AND is_accessible=1 AND $kindWhere ORDER BY media_id LIMIT ?",
            arrayOf(state.toString(), limit.toString())
        ).use { c ->
            ArrayList<Pending>(c.count).apply {
                while (c.moveToNext()) add(Pending(
                    id = c.getLong(0), uri = c.getString(1), displayName = c.str(2),
                    relativePath = c.str(3), ownerPackage = c.str(4), mime = c.str(5),
                    bytes = c.getLong(6), width = c.getInt(7), height = c.getInt(8),
                    dateModified = c.longOrNull(9) ?: 0L,
                    mediaKind = MediaKind.from(c.str(10), c.getLong(0)), durationMs = c.longOrNull(11),
                ))
            }
        }

    fun counts(): Counts = counts("1=1")

    private fun counts(kindWhere: String): Counts = readableDatabase.rawQuery(
        "SELECT COUNT(*), SUM(state>=${State.META}), SUM(state=${State.DONE}), SUM(state=${State.FAILED})" +
            " FROM photos WHERE is_accessible=1 AND $kindWhere", null
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
    /** @param blur 영상은 null이다 — 재지 않은 값을 0으로 넣으면 '가장 흐린 것'으로 읽힌다. */
    fun saveClip(id: Long, embedding: FloatArray, blur: Double?, brightness: Double?, tags: List<Tag>, modelId: String) {
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

    /**
     * 태그별 개수. 앨범 목록의 원본이다.
     * @param kind 어느 종류만 셀까. `"video"`면 영상으로만 이루어진 분류가 된다 —
     *  영상에 붙은 태그는 프레임 분류에서 왔고, 사진과 같은 라벨 체계를 쓴다.
     */
    fun tagCounts(kind: String = "all"): List<TagCount> = readableDatabase.rawQuery(
        "SELECT t.label, t.source, COUNT(*) n, SUM(p.media_id<0) v FROM tags t JOIN photos p ON p.media_id=t.media_id" +
            " WHERE p.is_accessible=1" + kindSql(kind, "p.media_id") + " GROUP BY t.label ORDER BY n DESC", null
    ).use { c ->
        ArrayList<TagCount>(c.count).apply {
            while (c.moveToNext()) add(TagCount(c.getString(0), c.str(1) ?: "clip", c.getInt(2), c.getInt(3)))
        }
    }

    /** 태그가 붙은 사진·영상. 최신순. */
    fun photosByTag(label: String, limit: Int = 500, offset: Int = 0, kind: String = "all"): List<PhotoCard> =
        readableDatabase.rawQuery(
            "SELECT p.media_id, p.uri, p.taken_at, p.taken_at_source, p.media_kind, p.duration_ms FROM photos p" +
                " JOIN tags t ON t.media_id=p.media_id WHERE t.label=? AND p.state>=${State.META} AND p.is_accessible=1" +
                kindSql(kind, "p.media_id") +
                " ORDER BY p.taken_at IS NULL, p.taken_at DESC LIMIT ? OFFSET ?",
            arrayOf(label, limit.toString(), offset.toString())
        ).use { it.toCards() }

    fun photosByIds(ids: List<Long>): Map<Long, PhotoCard> {
        if (ids.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos" +
                " WHERE is_accessible=1 AND media_id IN (${ids.joinToString(",")})", null
        ).use { it.toCards() }.associateBy { it.id }
    }

    fun recent(limit: Int): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos WHERE is_accessible=1 AND state>=${State.META}" +
            " ORDER BY taken_at IS NULL, taken_at DESC LIMIT ?", arrayOf(limit.toString())
    ).use { it.toCards() }

    fun photo(id: Long): PhotoDetail? = readableDatabase.rawQuery(
        "SELECT media_id, uri, display_name, taken_at, taken_at_source, gps_lat, gps_lon," +
            " camera_model, width, height, bytes, blur_score, brightness, mime, rel_path, owner_pkg, media_kind, duration_ms" +
            " FROM photos WHERE media_id=? AND is_accessible=1", arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToNext()) return null
        PhotoDetail(
            id = c.getLong(0), uri = c.getString(1), displayName = c.str(2),
            takenAt = c.longOrNull(3), takenAtSource = TimeSource.parse(c.str(4)),
            lat = c.dblOrNull(5), lon = c.dblOrNull(6), cameraModel = c.str(7),
            width = c.getInt(8), height = c.getInt(9), bytes = c.getLong(10),
            blurScore = c.dblOrNull(11), brightness = c.dblOrNull(12),
            mime = c.str(13), relativePath = c.str(14), ownerPackage = c.str(15),
            tags = tagsOf(id), mediaKind = MediaKind.from(c.str(16), id), durationMs = c.longOrNull(17),
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
        "SELECT media_id, embedding FROM photos WHERE embedding IS NOT NULL AND is_accessible=1", null
    ).use { c ->
        ArrayList<Pair<Long, FloatArray>>(c.count).apply {
            while (c.moveToNext()) add(c.getLong(0) to Embeddings.decode(c.getBlob(1))[0])
        }
    }

    /** 그룹핑 입력. 임베딩까지 싣는다. */
    fun groupingItems(): List<Grouping.Item> = readableDatabase.rawQuery(
        "SELECT media_id, display_name, sha256, taken_at, taken_at_source, blur_score, embedding" +
            " FROM photos WHERE state>=${State.META} AND is_accessible=1", null
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
                " FROM photos WHERE taken_at IS NOT NULL AND state>=${State.META} AND is_accessible=1", null
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

    /**
     * 기록(시간·장소로 묶인 것). 보이는 항목이 하나도 없는 기록(접근이 줄어든 경우)은 내놓지 않는다 —
     * 예전엔 "0개"짜리 기록이 목록에 남았다.
     */
    fun events(limit: Int = 100, kind: String = "all"): List<EventRow> = readableDatabase.rawQuery(
        "SELECT * FROM (SELECT e.id, e.started_at, e.ended_at, e.place_name, e.title," +
            " (SELECT COUNT(*) FROM event_photos ep JOIN photos p ON p.media_id=ep.media_id" +
            "   WHERE ep.event_id=e.id AND p.is_accessible=1" + kindSql(kind, "p.media_id") + ") n," +
            " (SELECT COUNT(*) FROM event_photos ep JOIN photos p ON p.media_id=ep.media_id" +
            "   WHERE ep.event_id=e.id AND p.is_accessible=1 AND p.media_id<0) v" +
            " FROM events e) WHERE n>0 ORDER BY started_at DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c ->
        ArrayList<EventRow>(c.count).apply {
            while (c.moveToNext()) add(EventRow(
                id = c.getLong(0), startedAt = c.getLong(1), endedAt = c.getLong(2),
                placeName = c.str(3), title = c.str(4) ?: "", count = c.getInt(5),
                coverIds = covers(c.getLong(0), kind), videos = c.getInt(6),
            ))
        }
    }

    /** 표지. 전체 보기는 묶을 때 고른 대표 사진을 쓰고, 종류를 가르면 그 종류에서 앞선 세 개를 쓴다. */
    private fun covers(eventId: Long, kind: String = "all"): List<Long> = readableDatabase.rawQuery(
        if (kind == "all")
            "SELECT ep.media_id FROM event_photos ep JOIN photos p ON p.media_id=ep.media_id" +
                " WHERE ep.event_id=? AND ep.is_cover=1 AND p.is_accessible=1 LIMIT 3"
        else
            "SELECT ep.media_id FROM event_photos ep JOIN photos p ON p.media_id=ep.media_id" +
                " WHERE ep.event_id=? AND p.is_accessible=1" + kindSql(kind, "p.media_id") +
                " ORDER BY p.taken_at LIMIT 3",
        arrayOf(eventId.toString())
    ).use { c -> ArrayList<Long>(3).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    fun eventPhotos(eventId: Long, kind: String = "all"): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT p.media_id, p.uri, p.taken_at, p.taken_at_source, p.media_kind, p.duration_ms FROM photos p" +
            " JOIN event_photos ep ON ep.media_id=p.media_id WHERE ep.event_id=? AND p.is_accessible=1" +
            kindSql(kind, "p.media_id") + " ORDER BY p.taken_at", arrayOf(eventId.toString())
    ).use { it.toCards() }

    fun dupeGroups(): List<Grouping.Group> {
        val byGroup = LinkedHashMap<Int, Triple<Grouping.Kind, MutableList<Long>, Long>>()
        readableDatabase.rawQuery(
            "SELECT d.group_id, d.media_id, d.kind, d.is_best FROM dupe_groups d" +
                " JOIN photos p ON p.media_id=d.media_id WHERE p.is_accessible=1 ORDER BY d.group_id", null
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
            "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos" +
                " WHERE media_id>0 AND is_accessible=1 AND blur_score IS NOT NULL AND blur_score < ? ORDER BY blur_score LIMIT ?",
            arrayOf(threshold.toString(), limit.toString())
        ).use { it.toCards() }

    /** 흐림 임계값은 카메라마다 달라 기본값을 박을 수 없다. 실제 분포를 보여 준다. */
    fun blurPercentiles(): Map<Int, Double> {
        val v = readableDatabase.rawQuery(
            "SELECT blur_score FROM photos WHERE media_id>0 AND is_accessible=1 AND blur_score IS NOT NULL ORDER BY blur_score", null
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
            " WHERE f.label=? AND f.verdict=1 AND p.embedding IS NOT NULL AND p.is_accessible=1", arrayOf(label)
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

    /** 이 사진들에 붙은 그 라벨의 태그 행. 지우기 전에 읽어 두면 되돌릴 수 있다. */
    fun tagRows(ids: Collection<Long>, label: String): List<TagRow> {
        if (ids.isEmpty()) return emptyList()
        return readableDatabase.rawQuery(
            "SELECT media_id, label, score, source FROM tags WHERE label=? AND media_id IN (${ids.joinToString(",")})",
            arrayOf(label),
        ).use { c ->
            ArrayList<TagRow>(c.count).apply {
                while (c.moveToNext()) add(TagRow(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getFloat(2), c.getString(3)))
            }
        }
    }

    /** [tagRows]로 읽어 둔 태그를 되돌리고, 그 지우기가 남긴 "틀렸다" 피드백도 지운다. */
    fun restoreTagRows(rows: List<TagRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                db.insertWithOnConflict("tags", null, ContentValues().apply {
                    put("media_id", r.id); put("label", r.label); put("score", r.score); put("source", r.source)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("user_feedback", "media_id=? AND label=?", arrayOf(r.id.toString(), r.label))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 내 앨범에 이미 들어 있는 것. 다시 넣었을 때 새로 들어간 것만 되돌리려고 쓴다. */
    fun userAlbumMembers(albumId: Long, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return readableDatabase.rawQuery(
            "SELECT media_id FROM user_album_photos WHERE album_id=? AND media_id IN (${ids.joinToString(",")})",
            arrayOf(albumId.toString()),
        ).use { c -> HashSet<Long>().apply { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /** 내 앨범에 저장된 영상 장면 시각. 빼기를 되돌릴 때 장면부터 재생이 유지되게 한다. */
    fun userAlbumScenes(albumId: Long, ids: Collection<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            "SELECT media_id, scene_time_ms FROM user_album_photos WHERE album_id=? AND scene_time_ms IS NOT NULL" +
                " AND media_id IN (${ids.joinToString(",")})",
            arrayOf(albumId.toString()),
        ).use { c -> HashMap<Long, Long>().apply { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) } }
    }

    fun clearAnalysis() {
        writableDatabase.execSQL("DELETE FROM video_frames")
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
        writableDatabase.execSQL("DELETE FROM video_frames")
        writableDatabase.execSQL(
            "UPDATE photos SET state=${State.META}, embedding=NULL WHERE state=${State.DONE}")
        writableDatabase.execSQL("DELETE FROM tags WHERE source='clip'")
    }

    // ---------- 전체 사진 ----------

    /**
     * 날짜순 전체 사진. **페이지로 끊어 읽는다** — 1만 장을 한 번에 카드로 만들면
     * 목록을 만드는 동안 화면이 멈춘다. 아래로 내릴 때마다 다음 장을 붙인다.
     */
    /**
     * 미디어 종류 조건 ("all" | "image" | "video"). 영상의 id는 음수라 부호로 가른다.
     * @param col `media_id` 열 이름. 조인에서는 별칭을 붙여 넘긴다(`p.media_id`).
     */
    private fun kindSql(kind: String, col: String = "media_id") = when (kind) {
        "video" -> " AND $col<0"
        "image" -> " AND $col>0"
        else -> ""
    }

    fun photos(limit: Int, offset: Int, kind: String = "all"): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos WHERE is_accessible=1 AND state>=${State.META}" + kindSql(kind) +
            " ORDER BY taken_at IS NULL, taken_at DESC, media_id DESC LIMIT ? OFFSET ?",
        arrayOf(limit.toString(), offset.toString())
    ).use { it.toCards() }

    /**
     * 월별 장수. **사진을 읽지 않고 개수만 센다** — 월 이동 막대는 1만 장을 다 불러오지
     * 않고도 "어느 달이 어디쯤인가"를 알아야 한다. 누적 합이 곧 그 달의 시작 offset이고,
     * [photos]에 그 offset을 주면 그 달로 바로 뛴다.
     *
     * 촬영시각이 없는 사진은 정렬에서 맨 뒤로 가므로 여기서도 빼야 offset이 맞는다.
     */
    fun months(kind: String = "all"): List<MonthRow> = readableDatabase.rawQuery(
        "SELECT strftime('%Y-%m', taken_at, 'unixepoch', 'localtime') ym, COUNT(*) n FROM photos" +
            " WHERE is_accessible=1 AND state>=${State.META}" + kindSql(kind) + " AND taken_at IS NOT NULL GROUP BY ym ORDER BY ym DESC", null
    ).use { c ->
        var acc = 0
        ArrayList<MonthRow>(c.count).apply {
            while (c.moveToNext()) {
                add(MonthRow(c.getString(0), c.getInt(1), acc))
                acc += c.getInt(1)
            }
        }
    }

    fun photoCount(kind: String = "all"): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM photos WHERE is_accessible=1 AND state>=${State.META}" + kindSql(kind), null)
        .use { if (it.moveToNext()) it.getInt(0) else 0 }

    // ---------- 기기 폴더 ----------

    /**
     * 카메라·스크린샷·다운로드처럼 **실제 저장 위치**. AI 앨범과 섞으면 사용자가
     * "이 묶음은 어디서 왔나"를 알 수 없다. bucket은 MediaStore가 이미 알고 있다.
     */
    fun buckets(minCount: Int = 1, kind: String = "all"): List<BucketRow> = readableDatabase.rawQuery(
        "SELECT bucket, COUNT(*) n, SUM(media_id<0) v FROM photos" +
            " WHERE is_accessible=1 AND bucket IS NOT NULL AND state>=${State.META}" + kindSql(kind) +
            // HAVING에 ?를 쓰면 안 된다: 바인딩 값은 문자열이고, SQLite는 집계값(n)에 형 변환을
            // 걸지 않아 "숫자 < 문자열"이 항상 참이 된다 → 폴더가 하나도 안 나왔다.
            " GROUP BY bucket HAVING n>=$minCount ORDER BY n DESC", null
    ).use { c ->
        ArrayList<BucketRow>(c.count).apply {
            while (c.moveToNext()) add(BucketRow(c.getString(0), c.getInt(1), bucketCovers(c.getString(0), kind), c.getInt(2)))
        }
    }

    private fun bucketCovers(bucket: String, kind: String): List<Long> = readableDatabase.rawQuery(
        "SELECT media_id FROM photos WHERE bucket=? AND is_accessible=1 AND state>=${State.META}" + kindSql(kind) +
            " ORDER BY taken_at IS NULL, taken_at DESC LIMIT 3", arrayOf(bucket)
    ).use { c -> ArrayList<Long>(3).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    fun photosByBucket(bucket: String, limit: Int, offset: Int, kind: String = "all"): List<PhotoCard> =
        readableDatabase.rawQuery(
            "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos" +
                " WHERE bucket=? AND is_accessible=1 AND state>=${State.META}" + kindSql(kind) +
                " ORDER BY taken_at IS NULL, taken_at DESC, media_id DESC LIMIT ? OFFSET ?",
            arrayOf(bucket, limit.toString(), offset.toString())
        ).use { it.toCards() }

    // ---------- 즐겨찾기 ----------

    fun favoriteIds(): Set<Long> = readableDatabase
        .rawQuery("SELECT f.media_id FROM favorites f JOIN photos p ON p.media_id=f.media_id WHERE p.is_accessible=1", null)
        .use { c -> HashSet<Long>(c.count).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    fun setFavorite(id: Long, on: Boolean) {
        if (on) writableDatabase.insertWithOnConflict("favorites", null, ContentValues().apply {
            put("media_id", id); put("ts", System.currentTimeMillis() / 1000)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        else writableDatabase.delete("favorites", "media_id=?", arrayOf(id.toString()))
    }

    fun favoriteCounts(kind: String = "all"): KindCount = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(p.media_id<0), 0) FROM favorites f JOIN photos p ON p.media_id=f.media_id" +
            " WHERE p.is_accessible=1" + kindSql(kind, "p.media_id"), null
    ).use { if (it.moveToFirst()) KindCount(it.getInt(0), it.getInt(1)) else KindCount(0, 0) }

    fun favorites(limit: Int, offset: Int, kind: String = "all"): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT p.media_id, p.uri, p.taken_at, p.taken_at_source, p.media_kind, p.duration_ms FROM photos p" +
            " JOIN favorites f ON f.media_id=p.media_id WHERE p.is_accessible=1" + kindSql(kind, "p.media_id") +
            " ORDER BY p.taken_at IS NULL, p.taken_at DESC LIMIT ? OFFSET ?",
        arrayOf(limit.toString(), offset.toString())
    ).use { it.toCards() }

    // ---------- 내 앨범 ----------

    /**
     * 내 앨범. 종류를 가르면 그 종류의 개수를 센다(0개인 앨범도 행은 남긴다 — 걸러낼지는 화면이 정한다).
     * "전체" 보기에서는 빈 앨범이 남아 있어야 사용자가 만든 것이 사라졌다고 느끼지 않는다.
     */
    fun userAlbums(kind: String = "all"): List<UserAlbumRow> = readableDatabase.rawQuery(
        "SELECT a.id, a.name," +
            " (SELECT COUNT(*) FROM user_album_photos ap JOIN photos p ON p.media_id=ap.media_id" +
            "   WHERE ap.album_id=a.id AND p.is_accessible=1" + kindSql(kind, "p.media_id") + ") n," +
            " (SELECT COUNT(*) FROM user_album_photos ap JOIN photos p ON p.media_id=ap.media_id" +
            "   WHERE ap.album_id=a.id AND p.is_accessible=1 AND p.media_id<0) v" +
            " FROM user_albums a ORDER BY a.created_at DESC", null
    ).use { c ->
        ArrayList<UserAlbumRow>(c.count).apply {
            while (c.moveToNext()) {
                val id = c.getLong(0)
                add(UserAlbumRow(id, c.getString(1), c.getInt(2), userAlbumCovers(id, kind), c.getInt(3)))
            }
        }
    }

    private fun userAlbumCovers(albumId: Long, kind: String): List<Long> = readableDatabase.rawQuery(
        "SELECT p.media_id FROM photos p JOIN user_album_photos ap ON ap.media_id=p.media_id" +
            " WHERE ap.album_id=? AND p.is_accessible=1" + kindSql(kind, "p.media_id") +
            " ORDER BY ap.added_at DESC LIMIT 3", arrayOf(albumId.toString())
    ).use { c -> ArrayList<Long>(3).apply { while (c.moveToNext()) add(c.getLong(0)) } }

    fun createUserAlbum(name: String): Long = writableDatabase.insert("user_albums", null,
        ContentValues().apply { put("name", name); put("created_at", System.currentTimeMillis() / 1000) })

    fun deleteUserAlbum(id: Long) {
        val db = writableDatabase
        db.delete("user_album_photos", "album_id=?", arrayOf(id.toString()))
        db.delete("user_albums", "id=?", arrayOf(id.toString()))
    }

    fun addToUserAlbum(albumId: Long, ids: Collection<Long>, sceneTimes: Map<Long, Long> = emptyMap()) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis() / 1000
        db.beginTransaction()
        try {
            for (id in ids) db.insertWithOnConflict("user_album_photos", null, ContentValues().apply {
                put("album_id", albumId); put("media_id", id); put("added_at", now)
                put("scene_time_ms", sceneTimes[id])
            }, SQLiteDatabase.CONFLICT_IGNORE)
            for ((id, timeMs) in sceneTimes) db.update("user_album_photos",
                ContentValues().apply { put("scene_time_ms", timeMs) },
                "album_id=? AND media_id=?", arrayOf(albumId.toString(), id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun removeFromUserAlbum(albumId: Long, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "DELETE FROM user_album_photos WHERE album_id=? AND media_id IN (${ids.joinToString(",")})",
            arrayOf(albumId))
    }

    fun userAlbumPhotos(albumId: Long, limit: Int, offset: Int, kind: String = "all"): List<PhotoCard> =
        readableDatabase.rawQuery(
            "SELECT p.media_id, p.uri, p.taken_at, p.taken_at_source, p.media_kind, p.duration_ms, ap.scene_time_ms FROM photos p" +
                " JOIN user_album_photos ap ON ap.media_id=p.media_id WHERE ap.album_id=? AND p.is_accessible=1" +
                kindSql(kind, "p.media_id") +
                " ORDER BY p.taken_at IS NULL, p.taken_at DESC LIMIT ? OFFSET ?",
            arrayOf(albumId.toString(), limit.toString(), offset.toString())
        ).use { it.toCards() }

    // ---------- 중복 묶음에 대한 사용자의 결정 ----------

    /**
     * 묶음 열쇠는 **묶음에서 가장 작은 media_id**다(Grouping.key). group_id는 재분석마다
     * 다시 매겨지므로 그것을 열쇠로 쓰면 "이 묶음은 유지"가 엉뚱한 묶음에 붙는다.
     */
    fun dupeChoices(): Map<String, DupeChoice> = readableDatabase
        .rawQuery("SELECT group_key, keep_id, dismissed FROM dupe_choice", null)
        .use { c ->
            HashMap<String, DupeChoice>(c.count).apply {
                while (c.moveToNext())
                    put(c.getString(0), DupeChoice(c.longOrNull(1), c.getInt(2) == 1))
            }
        }

    fun setDupeKeep(key: String, mediaId: Long) = upsertChoice(key) { put("keep_id", mediaId) }

    fun setDupeDismissed(key: String, on: Boolean) = upsertChoice(key) { put("dismissed", if (on) 1 else 0) }

    private fun upsertChoice(key: String, fill: ContentValues.() -> Unit) {
        val db = writableDatabase
        val v = ContentValues().apply(fill)
        if (db.update("dupe_choice", v, "group_key=?", arrayOf(key)) == 0) {
            v.put("group_key", key)
            db.insertWithOnConflict("dupe_choice", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** 파일마다의 저장 폴더와 크기. */
    fun fileInfo(ids: Collection<Long>): Map<Long, FileInfo> {
        if (ids.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            "SELECT media_id, bucket, bytes FROM photos WHERE media_id IN (${ids.joinToString(",")})", null
        ).use { c ->
            HashMap<Long, FileInfo>(c.count).apply {
                while (c.moveToNext()) put(c.getLong(0), FileInfo(if (c.isNull(1)) null else c.getString(1), c.getLong(2)))
            }
        }
    }

    /** 고른 사진이 실제로 차지하는 용량. "얼마나 비는가"를 숫자로 보여 주기 위한 것. */
    fun bytesOf(ids: Collection<Long>): Long {
        if (ids.isEmpty()) return 0
        return readableDatabase.rawQuery(
            "SELECT SUM(bytes) FROM photos WHERE is_accessible=1 AND media_id IN (${ids.joinToString(",")})", null
        ).use { if (it.moveToNext() && !it.isNull(0)) it.getLong(0) else 0L }
    }

    // ---------- 추억 ----------

    fun photosBetween(from: Long, until: Long, limit: Int): List<PhotoCard> = readableDatabase.rawQuery(
        "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos" +
            " WHERE is_accessible=1 AND taken_at BETWEEN ? AND ? AND state>=${State.META}" +
            " ORDER BY taken_at DESC LIMIT ?",
        arrayOf(from.toString(), until.toString(), limit.toString())
    ).use { it.toCards() }

    /**
     * 지난 해 같은 날. **EXIF 촬영시각만 본다** — 내려받은 사진의 '파일 시각'은 찍은 날이
     * 아니라 받은 날이라, 그것을 추억이라고 내밀면 거짓말이 된다(§4.5).
     */
    fun onThisDay(monthDay: String, thisYear: String, limit: Int): List<PhotoCard> =
        readableDatabase.rawQuery(
            "SELECT media_id, uri, taken_at, taken_at_source, media_kind, duration_ms FROM photos" +
                " WHERE is_accessible=1 AND taken_at IS NOT NULL AND taken_at_source='exif' AND state>=${State.META}" +
                " AND strftime('%m-%d', taken_at, 'unixepoch', 'localtime')=?" +
                " AND strftime('%Y', taken_at, 'unixepoch', 'localtime')<?" +
                " ORDER BY taken_at DESC LIMIT ?",
            arrayOf(monthDay, thisYear, limit.toString())
        ).use { it.toCards() }

    // ---------- 삭제 반영 ----------

    /**
     * 휴지통으로 보낸 사진을 **즉시** 우리 DB에서도 지운다. 워커의 다음 동기화를
     * 기다리면 방금 보낸 사진이 앨범에 그대로 보이고, 그러면 사용자가 결과를 믿지 못한다.
     */
    fun forget(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in ids.chunked(500)) {
                val q = chunk.joinToString(",")
                for (t in listOf("photos", "tags", "event_photos", "dupe_groups",
                        "favorites", "user_album_photos", "video_frames"))
                    db.execSQL("DELETE FROM $t WHERE media_id IN ($q)")
            }
            metaPut(GROUPING_COMPLETE, "0")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------- 태그 편집 ----------

    /** 앨범에서 빼기. **사진은 그대로 둔다** — 묶음에서 빼는 것과 지우는 것은 다르다. */
    fun removeTag(ids: Collection<Long>, label: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM tags WHERE label=? AND media_id IN (${ids.joinToString(",")})",
                arrayOf(label))
            for (id in ids) feedback(id, label, correct = false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * 분류 고정. source를 'user'로 바꾸면 [requeueClip]이 지우지 않으므로 재분석해도
     * 살아남는다 — "고정"이 실제로 뜻하는 것이 그것이다.
     */
    fun pinTag(id: Long, label: String) {
        writableDatabase.insertWithOnConflict("tags", null, ContentValues().apply {
            put("media_id", id); put("label", label); put("source", "user")
        }, SQLiteDatabase.CONFLICT_REPLACE)
        feedback(id, label, correct = true)
    }

    private companion object {
        val ALL_TABLES = listOf("photos", "tags", "events", "event_photos", "dupe_groups",
            "user_feedback", "meta", "favorites", "user_albums", "user_album_photos", "dupe_choice", "video_frames")

        /** 재생성 때 살릴 표와 열. 전부 사용자가 직접 넣은 정보다. */
        val KEEP_TABLES = listOf(
            "user_feedback" to listOf("media_id", "label", "verdict", "ts"),
            "favorites" to listOf("media_id", "ts"),
            "user_albums" to listOf("id", "name", "created_at"),
            "user_album_photos" to listOf("album_id", "media_id", "added_at", "scene_time_ms"),
            "dupe_choice" to listOf("group_key", "keep_id", "dismissed"),
        )
    }
}


// ---------- 커서 헬퍼 ----------

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
private fun Cursor.dblOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

private fun Cursor.toCards(): List<PhotoCard> = ArrayList<PhotoCard>(count).also { out ->
    val kindColumn = getColumnIndex("media_kind")
    val durationColumn = getColumnIndex("duration_ms")
    val sceneColumn = getColumnIndex("scene_time_ms")
    while (moveToNext()) {
        val id = getLong(0)
        out.add(PhotoCard(
            id = id, uri = getString(1), takenAt = longOrNull(2),
            takenAtSource = TimeSource.parse(str(3)),
            mediaKind = MediaKind.from(if (kindColumn >= 0) str(kindColumn) else null, id),
            durationMs = if (durationColumn >= 0) longOrNull(durationColumn) else null,
            sceneTimeMs = if (sceneColumn >= 0) longOrNull(sceneColumn) else null,
        ))
    }
}
