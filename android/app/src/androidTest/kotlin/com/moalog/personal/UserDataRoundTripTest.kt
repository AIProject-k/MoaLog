package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.core.BackupCodec
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.Tag
import com.moalog.personal.data.BackupRepository
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 최종 검증 시나리오 A: 사용자 데이터는 백업 → AI 재분석 → 백업 복원 뒤에도 그대로 남는다.
 *
 * 흐름: 분석 → 사용자가 즐겨찾기·앨범·고정·제거 → 백업 → 전체 재분석 → 사용자 정보만 지움(새 기기 흉내)
 *       → 복원 → 다시 재분석이 와도 확인.
 */
@RunWith(AndroidJUnit4::class)
class UserDataRoundTripTest {

    private fun withStore(block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "round-trip-test.db")
        SQLiteDatabase.deleteDatabase(dbFile)
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try { block(store) } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }

    private fun Store.addPhoto(id: Long, name: String, bytes: Long, taken: Long) = writableDatabase.execSQL(
        "INSERT INTO photos (media_id, uri, display_name, bytes, taken_at, rel_path, is_accessible, state) " +
            "VALUES (?, ?, ?, ?, ?, 'DCIM/Camera/', 1, ${Store.State.META})",
        arrayOf(id, "content://t/$id", name, bytes, taken),
    )

    private fun Store.tagSources(id: Long): Map<String, String> = readableDatabase.rawQuery(
        "SELECT label, source FROM tags WHERE media_id=?", arrayOf(id.toString())
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    private fun Store.longs(sql: String): List<Long> = readableDatabase.rawQuery(sql, null).use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0)) }
    }

    @Test fun 백업하고_재분석하고_복원해도_사용자_정보가_그대로다() = withStore { store ->
        val emb = FloatArray(Embeddings.DIM)
        store.addPhoto(1L, "IMG_1.jpg", 100L, 1_690_000_001L)
        store.addPhoto(2L, "IMG_2.jpg", 200L, 1_690_000_002L)
        store.addPhoto(3L, "IMG_3.jpg", 300L, 1_690_000_003L)

        // 1) 첫 분석
        for (i in 1L..3L) store.saveClip(i, emb, null, null, listOf(Tag("풍경", .5f, "clip"), Tag("음식", .6f, "clip")), "m")

        // 2) 사용자가 한 일
        store.setFavorite(1L, true)
        val album = store.createUserAlbum("여행")
        store.addToUserAlbum(album, listOf(1L, 2L))
        store.pinTag(1L, "실내")             // 없던 분류를 직접 추가하고 고정
        store.removeTag(listOf(2L), "음식")  // AI가 붙인 분류를 직접 뺌

        // 3) 백업 (쓰고 다시 읽는 왕복)
        val backup = BackupCodec.encode(BackupRepository(store).collect(1_760_000_000L))

        // 4) AI 재분석: 전부 다시 분석하면 AI는 다시 같은 분류를 붙이려 한다
        store.requeueClip()
        for (i in 1L..3L) store.saveClip(i, emb, null, null, listOf(Tag("풍경", .5f, "clip"), Tag("음식", .6f, "clip")), "m")
        assertEquals("재분석이 뺀 분류를 되살리면 안 된다", mapOf("풍경" to "clip"), store.tagSources(2L))
        assertEquals("재분석이 고정을 풀면 안 된다", "user", store.tagSources(1L)["실내"])

        // 5) 새 기기 흉내: 사용자 정보만 지운다. 사진과 AI 분류는 남는다.
        store.writableDatabase.apply {
            execSQL("DELETE FROM favorites")
            execSQL("DELETE FROM user_album_photos")
            execSQL("DELETE FROM user_albums")
            execSQL("DELETE FROM user_feedback")
            execSQL("DELETE FROM dupe_choice")
            execSQL("DELETE FROM tags WHERE source='user'")
        }

        // 6) 복원
        val restored = BackupRepository(store).restore(BackupCodec.decode(backup))
        assertEquals(0, restored.unmatched)
        assertEquals(setOf(1L), store.favoriteIds())
        assertEquals(listOf(1L, 2L), store.longs("SELECT media_id FROM user_album_photos ORDER BY media_id"))
        assertEquals(mapOf("실내" to "user", "풍경" to "clip", "음식" to "clip"), store.tagSources(1L))
        assertEquals(mapOf("풍경" to "clip"), store.tagSources(2L))

        // 7) 복원 뒤에 또 재분석이 와도 그대로다
        store.saveClip(2L, emb, null, null, listOf(Tag("음식", .6f, "clip"), Tag("풍경", .5f, "clip")), "m")
        assertEquals(mapOf("풍경" to "clip"), store.tagSources(2L))
        assertEquals(mapOf("실내" to "user", "풍경" to "clip", "음식" to "clip"), store.tagSources(1L))
    }
}
