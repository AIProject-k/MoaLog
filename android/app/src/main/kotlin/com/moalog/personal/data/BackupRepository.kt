package com.moalog.personal.data

import android.database.Cursor
import com.moalog.personal.core.BackupAlbum
import com.moalog.personal.core.BackupAlbumItem
import com.moalog.personal.core.BackupDoc
import com.moalog.personal.core.BackupFavorite
import com.moalog.personal.core.BackupFeedback
import com.moalog.personal.core.BackupMatcher
import com.moalog.personal.core.BackupMedia

/**
 * 사용자 데이터 ↔ 백업 문서 (계획서 MLG-105). 형식과 짝짓기 규칙은 `core/Backup.kt`에 있고, 여기는 DB만 만진다.
 *
 * 넣는 것: 즐겨찾기, 직접 만든 앨범과 구성, 분류 교정(`user_feedback`).
 * 안 넣는 것: 사진 원본, 임베딩·태그(파생 데이터라 다시 분석하면 된다), 중복 묶음 결정(아래 주석 참고).
 */
class BackupRepository(private val store: Store) {

    /** 복원 결과. [unmatched]는 기기에서 짝을 못 찾은 사진 수다 — 그 사진에 달린 정보는 건너뛰었다. */
    data class Restored(val favorites: Int, val albums: Int, val albumItems: Int, val feedback: Int, val unmatched: Int)

    private data class Print(val name: String?, val bytes: Long, val takenAt: Long?, val path: String?)

    /** 지금 DB의 사용자 데이터를 백업 문서로. 사진이 DB에 없으면 그 항목은 빼고 쓴다. */
    fun collect(now: Long): BackupDoc {
        val db = store.readableDatabase
        val favorites = db.rawQuery("SELECT media_id, ts FROM favorites", null).use { c ->
            rows(c) { it.getLong(0) to it.getLong(1) }
        }
        val albums = db.rawQuery("SELECT id, name, created_at FROM user_albums ORDER BY id", null).use { c ->
            rows(c) { Triple(it.getLong(0), it.getString(1), it.getLong(2)) }
        }
        val items = db.rawQuery("SELECT album_id, media_id, added_at, scene_time_ms FROM user_album_photos", null).use { c ->
            rows(c) { AlbumRow(it.getLong(0), it.getLong(1), it.getLong(2), if (it.isNull(3)) null else it.getLong(3)) }
        }
        val feedback = db.rawQuery("SELECT media_id, label, verdict, ts FROM user_feedback", null).use { c ->
            rows(c) { FeedbackRow(it.getLong(0), it.getString(1), it.getInt(2), it.getLong(3)) }
        }

        val prints = fingerprints(
            (favorites.map { it.first } + items.map { it.mediaId } + feedback.map { it.mediaId }).toSet()
        )
        // 사진 번호 대신 이 파일 안의 번호(ref)를 쓴다. 등장 순서대로 0부터 붙인다.
        val refs = LinkedHashMap<Long, Int>()
        fun ref(id: Long): Int? = if (id !in prints) null else refs.getOrPut(id) { refs.size }

        val favs = favorites.mapNotNull { (id, ts) -> ref(id)?.let { BackupFavorite(it, ts) } }
        val albumDocs = albums.map { (albumId, name, created) ->
            BackupAlbum(
                name = name, created = created,
                items = items.filter { it.albumId == albumId }.mapNotNull { row ->
                    ref(row.mediaId)?.let { BackupAlbumItem(it, row.addedAt, row.sceneMs) }
                },
            )
        }
        val fb = feedback.mapNotNull { row -> ref(row.mediaId)?.let { BackupFeedback(it, row.label, row.verdict, row.ts) } }
        val media = refs.map { (id, r) ->
            val p = prints.getValue(id)
            BackupMedia(r, p.name, p.bytes, p.takenAt, p.path)
        }
        return BackupDoc(createdAt = now, media = media, favorites = favs, albums = albumDocs, feedback = fb)
    }

    /**
     * 백업을 DB에 합친다. 같은 앨범 이름은 기존 것에 붙이고, 교정은 같은 사진·라벨이면 백업 쪽이 이긴다.
     * 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다.
     */
    fun restore(doc: BackupDoc): Restored {
        val db = store.writableDatabase
        val candidates = db.rawQuery("SELECT media_id, display_name, bytes, taken_at, rel_path FROM photos WHERE is_accessible=1", null).use { c ->
            rows(c) {
                BackupMatcher.Candidate(
                    mediaId = it.getLong(0), name = it.getString(1),
                    bytes = if (it.isNull(2)) 0L else it.getLong(2),
                    takenAt = if (it.isNull(3)) null else it.getLong(3), path = it.getString(4),
                )
            }
        }
        val link = BackupMatcher.resolve(doc.media, candidates)
        var favorites = 0
        var albums = 0
        var albumItems = 0
        var feedback = 0

        db.beginTransaction()
        try {
            for (f in doc.favorites) link[f.ref]?.let { id ->
                db.compileStatement("INSERT OR IGNORE INTO favorites (media_id, ts) VALUES (?, ?)").use { s ->
                    s.bindLong(1, id); s.bindLong(2, f.ts)
                    if (s.executeInsert() != -1L) favorites++
                }
            }
            for (a in doc.albums) {
                val albumId = db.rawQuery("SELECT id FROM user_albums WHERE name=? ORDER BY id LIMIT 1", arrayOf(a.name)).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else -1L
                }.let { existing ->
                    if (existing >= 0) existing else db.compileStatement("INSERT INTO user_albums (name, created_at) VALUES (?, ?)").use { s ->
                        s.bindString(1, a.name); s.bindLong(2, a.created); s.executeInsert()
                    }.also { albums++ }
                }
                for (i in a.items) link[i.ref]?.let { id ->
                    db.compileStatement("INSERT OR IGNORE INTO user_album_photos (album_id, media_id, added_at, scene_time_ms) VALUES (?, ?, ?, ?)").use { s ->
                        s.bindLong(1, albumId); s.bindLong(2, id); s.bindLong(3, i.added)
                        i.sceneMs?.let { s.bindLong(4, it) } ?: s.bindNull(4)
                        if (s.executeInsert() != -1L) albumItems++
                    }
                }
            }
            for (f in doc.feedback) link[f.ref]?.let { id ->
                db.compileStatement("INSERT OR REPLACE INTO user_feedback (media_id, label, verdict, ts) VALUES (?, ?, ?, ?)").use { s ->
                    s.bindLong(1, id); s.bindString(2, f.label); s.bindLong(3, f.verdict.toLong()); s.bindLong(4, f.ts)
                    s.executeInsert()
                }
                feedback++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return Restored(favorites, albums, albumItems, feedback, unmatched = doc.media.count { it.ref !in link })
    }

    /**
     * 사진 지문. 사진 번호는 기기마다 다르므로 백업에는 이름·크기·촬영 시각·폴더를 쓴다.
     * 한 번에 너무 많이 묶으면 SQLite 변수 한도를 넘으므로 나눠 읽는다.
     */
    private fun fingerprints(ids: Set<Long>): Map<Long, Print> {
        val out = HashMap<Long, Print>(ids.size)
        for (chunk in ids.toList().chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            store.readableDatabase.rawQuery(
                "SELECT media_id, display_name, bytes, taken_at, rel_path FROM photos WHERE media_id IN ($marks)",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = Print(
                    name = c.getString(1), bytes = if (c.isNull(2)) 0L else c.getLong(2),
                    takenAt = if (c.isNull(3)) null else c.getLong(3), path = c.getString(4),
                )
            }
        }
        return out
    }

    private data class AlbumRow(val albumId: Long, val mediaId: Long, val addedAt: Long, val sceneMs: Long?)
    private data class FeedbackRow(val mediaId: Long, val label: String, val verdict: Int, val ts: Long)

    private inline fun <T> rows(c: Cursor, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(c.count)
        while (c.moveToNext()) out.add(map(c))
        return out
    }
}
