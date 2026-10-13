package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.core.BackupAlbum
import com.moalog.personal.core.BackupAlbumItem
import com.moalog.personal.core.BackupDoc
import com.moalog.personal.core.BackupFavorite
import com.moalog.personal.core.BackupFeedback
import com.moalog.personal.core.BackupMedia
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.Tag
import com.moalog.personal.data.BackupRepository
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 백업·복원이 DB에서 맞게 읽고 쓰는지 본다 (계획서 MLG-105).
 *
 * 핵심은 **사진 번호가 달라도 붙는다**는 것이다. 다른 기기에서는 같은 사진의 MediaStore 번호가 다르다.
 */
@RunWith(AndroidJUnit4::class)
class StoreBackupTest {

    private fun withStore(name: String, block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "backup-$name.db")
        SQLiteDatabase.deleteDatabase(dbFile)
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            block(store)
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }

    private fun Store.addPhoto(id: Long, name: String, bytes: Long, taken: Long) {
        writableDatabase.execSQL(
            "INSERT INTO photos (media_id, uri, display_name, bytes, taken_at, rel_path, is_accessible, state) " +
                "VALUES (?, ?, ?, ?, ?, 'DCIM/Camera/', 1, 2)",
            arrayOf(id, "content://t/$id", name, bytes, taken),
        )
    }

    private fun Store.longs(sql: String): List<Long> = readableDatabase.rawQuery(sql, null).use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0)) }
    }

    /** 원래 기기의 DB 사용자 데이터는 백업 문서로 빠짐없이 나온다. 사진 번호는 문서에 없다. */
    @Test fun 백업은_사진_번호가_아니라_지문으로_적는다() = withStore("collect") { store ->
        store.addPhoto(1, "IMG_1.jpg", 1234, 1_690_000_000L)
        store.addPhoto(2, "IMG_2.jpg", 555, 1_690_000_100L)
        store.addPhoto(3, "unused.jpg", 9, 1L)
        store.writableDatabase.apply {
            execSQL("INSERT INTO favorites (media_id, ts) VALUES (1, 100)")
            execSQL("INSERT INTO user_albums (id, name, created_at) VALUES (1, '여행', 50)")
            execSQL("INSERT INTO user_album_photos (album_id, media_id, added_at, scene_time_ms) VALUES (1, 1, 60, NULL)")
            execSQL("INSERT INTO user_album_photos (album_id, media_id, added_at, scene_time_ms) VALUES (1, 2, 61, 4500)")
            execSQL("INSERT INTO user_feedback (media_id, label, verdict, ts) VALUES (2, '바다', 1, 70)")
        }
        val doc = BackupRepository(store).collect(999L)
        assertEquals(999L, doc.createdAt)
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), doc.media.map { it.name })
        assertEquals(listOf(0), doc.favorites.map { it.ref })
        assertEquals(listOf(0, 1), doc.albums.single().items.map { it.ref })
        assertEquals(4500L, doc.albums.single().items[1].sceneMs)
        assertEquals(1, doc.feedback.single().ref)
        // 쓰이지 않은 사진(unused.jpg)은 백업에 들어가지 않는다
        assertEquals(false, doc.media.any { it.name == "unused.jpg" })
    }

    private val doc = BackupDoc(
        createdAt = 1L,
        media = listOf(
            BackupMedia(0, "IMG_1.jpg", 1234, 1_690_000_000L, "DCIM/Camera/"),
            BackupMedia(1, "IMG_2.jpg", 555, 1_690_000_100L, "DCIM/Camera/"),
        ),
        favorites = listOf(BackupFavorite(0, 100L)),
        albums = listOf(BackupAlbum("여행", 50L, listOf(BackupAlbumItem(0, 60L, null), BackupAlbumItem(1, 61L, 4500L)))),
        feedback = listOf(BackupFeedback(1, "바다", 1, 70L)),
    )

    /** 다른 기기: 같은 사진이지만 번호가 101·102다. 기존 것과 맞물리지 않고 올바른 사진에 붙어야 한다. */
    @Test fun 복원은_지금_기기의_사진_번호에_붙인다() = withStore("restore") { store ->
        store.addPhoto(101, "IMG_1.jpg", 1234, 1_690_000_000L)
        store.addPhoto(102, "IMG_2.jpg", 555, 1_690_000_100L)
        val r = BackupRepository(store).restore(doc)
        assertEquals(BackupRepository.Restored(favorites = 1, albums = 1, albumItems = 2, feedback = 1, unmatched = 0), r)
        assertEquals(listOf(101L), store.longs("SELECT media_id FROM favorites"))
        assertEquals(listOf(101L, 102L), store.longs("SELECT media_id FROM user_album_photos ORDER BY media_id"))
        assertEquals(listOf(102L), store.longs("SELECT media_id FROM user_feedback"))
    }

    /** 같은 백업을 두 번 복원해도 즐겨찾기·앨범 항목이 두 배가 되지 않는다. 같은 이름의 앨범에는 합친다. */
    @Test fun 두_번_복원해도_중복이_생기지_않는다() = withStore("twice") { store ->
        store.addPhoto(101, "IMG_1.jpg", 1234, 1_690_000_000L)
        store.addPhoto(102, "IMG_2.jpg", 555, 1_690_000_100L)
        val repo = BackupRepository(store)
        repo.restore(doc)
        val second = repo.restore(doc)
        assertEquals(0, second.favorites)
        assertEquals(0, second.albums)
        assertEquals(0, second.albumItems)
        assertEquals(listOf(1L), store.longs("SELECT COUNT(*) FROM user_albums"))
        assertEquals(listOf(2L), store.longs("SELECT COUNT(*) FROM user_album_photos"))
    }

    /** 기기에 없는 사진에 달린 항목은 건너뛰고 몇 장을 못 찾았는지 알려 준다. 엉뚱한 사진에 붙이지 않는다. */
    @Test fun 짝을_못_찾은_사진의_정보는_건너뛴다() = withStore("unmatched") { store ->
        store.addPhoto(101, "IMG_1.jpg", 1234, 1_690_000_000L)
        val r = BackupRepository(store).restore(doc)
        assertEquals(1, r.unmatched)
        assertEquals(1, r.favorites)
        assertEquals(1, r.albumItems)
        assertEquals(0, r.feedback)
        assertEquals(listOf(101L), store.longs("SELECT media_id FROM user_album_photos"))
    }

    private fun Store.tagSources(id: Long): Map<String, String> = readableDatabase.rawQuery(
        "SELECT label, source FROM tags WHERE media_id=?", arrayOf(id.toString())
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    /** 교정은 태그에도 돌아와야 한다. 맞음은 사용자 태그로 고정, 틀림은 태그를 지운다. 분석이 나중에 와도 유지된다. */
    @Test fun 복원은_고정하거나_뺀_분류를_태그에_다시_적용한다() = withStore("tags") { store ->
        store.addPhoto(101, "IMG_1.jpg", 1234, 1_690_000_000L)
        // 이 기기에는 분석이 만든 풍경 태그가 이미 있다
        store.writableDatabase.execSQL("INSERT INTO tags (media_id, label, score, source) VALUES (101, '풍경', 0.5, 'clip')")
        val tagDoc = doc.copy(
            media = listOf(BackupMedia(0, "IMG_1.jpg", 1234, 1_690_000_000L, "DCIM/Camera/")),
            favorites = emptyList(), albums = emptyList(),
            feedback = listOf(BackupFeedback(0, "음식", 1, 70L), BackupFeedback(0, "풍경", 0, 71L)),
        )
        BackupRepository(store).restore(tagDoc)
        assertEquals(mapOf("음식" to "user"), store.tagSources(101))

        // 뒤늦게 분석이 와도 고정은 풀리지 않고 뺀 분류는 돌아오지 않는다
        store.saveClip(101, FloatArray(Embeddings.DIM), null, null,
            listOf(Tag("풍경", .5f, "clip"), Tag("음식", .6f, "clip"), Tag("실내", .4f, "clip")), "m")
        assertEquals(mapOf("음식" to "user", "실내" to "clip"), store.tagSources(101))
    }

    /** 이름이 같은 앨범 둘이 한 앨범으로 합쳐지면 서로 다른 모음이 섞인다. 이름과 만든 시각이 모두 같아야 같은 앨범이다. */
    @Test fun 이름이_같아도_만든_시각이_다른_앨범은_따로_복원한다() = withStore("samename") { store ->
        store.addPhoto(101, "IMG_1.jpg", 1234, 1_690_000_000L)
        store.addPhoto(102, "IMG_2.jpg", 555, 1_690_000_100L)
        val twoAlbums = doc.copy(
            favorites = emptyList(), feedback = emptyList(),
            albums = listOf(
                BackupAlbum("여행", 50L, listOf(BackupAlbumItem(0, 60L, null))),
                BackupAlbum("여행", 99L, listOf(BackupAlbumItem(1, 61L, null))),
            ),
        )
        val r = BackupRepository(store).restore(twoAlbums)
        assertEquals(2, r.albums)
        assertEquals(listOf(2L), store.longs("SELECT COUNT(*) FROM user_albums"))
        // 각 앨범에는 자기 사진만 들어 있다
        assertEquals(listOf(101L), store.longs("SELECT media_id FROM user_album_photos WHERE album_id=(SELECT id FROM user_albums WHERE created_at=50)"))
        assertEquals(listOf(102L), store.longs("SELECT media_id FROM user_album_photos WHERE album_id=(SELECT id FROM user_albums WHERE created_at=99)"))
        // 같은 백업을 다시 복원해도 늘지 않는다
        BackupRepository(store).restore(twoAlbums)
        assertEquals(listOf(2L), store.longs("SELECT COUNT(*) FROM user_albums"))
    }
}
