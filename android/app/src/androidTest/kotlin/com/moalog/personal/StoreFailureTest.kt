package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 분석 실패를 어떻게 세는지 못박는다 (계획서 MLG-104).
 *
 * 예전에는 한 번 실패한 사진이 바로 FAILED가 되어 다시 시도할 길이 없었다. 일시적인 추론 오류 한 번으로
 * 사진이 영구히 빠졌다. 지금은 [Store.noteFailure]가 횟수를 세고, 한도에 닿을 때까지 META로 남긴다.
 * 같은 실행 안에서 그 사진을 또 집어 무한 반복하지 않도록 [Store.pendingPhotos]는 커서를 받는다.
 */
@RunWith(AndroidJUnit4::class)
class StoreFailureTest {

    private fun withStore(block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "failure-test.db")
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

    /** 메타 단계는 끝났고(META) 아직 CLIP을 못 받은 사진 한 장. */
    private fun Store.insertMeta(id: Long) {
        writableDatabase.execSQL(
            "INSERT INTO photos (media_id, uri, is_accessible, state) VALUES (?, ?, 1, ${Store.State.META})",
            arrayOf(id, "content://test/$id")
        )
    }

    private fun Store.stateOf(id: Long): Int = readableDatabase.rawQuery(
        "SELECT state FROM photos WHERE media_id=?", arrayOf(id.toString())
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test fun 실패는_세_번째부터_영구_실패로_둔다() = withStore { store ->
        store.insertMeta(1L)
        store.noteFailure(1L, 3)
        store.noteFailure(1L, 3)
        assertEquals("두 번까지는 다음 실행이 다시 시도해야 한다", Store.State.META, store.stateOf(1L))
        store.noteFailure(1L, 3)
        assertEquals(Store.State.FAILED, store.stateOf(1L))
    }

    @Test fun 커서는_같은_실행에서_이미_본_사진을_다시_주지_않는다() = withStore { store ->
        for (i in 1L..5L) store.insertMeta(i)
        assertEquals(listOf(1L, 2L), store.pendingPhotos(Store.State.META, 2).map { it.id })
        assertEquals(listOf(3L, 4L), store.pendingPhotos(Store.State.META, 2, afterId = 2L).map { it.id })
        assertEquals(listOf(5L), store.pendingPhotos(Store.State.META, 2, afterId = 4L).map { it.id })
        assertEquals(emptyList<Long>(), store.pendingPhotos(Store.State.META, 2, afterId = 5L).map { it.id })
    }

    @Test fun 영상_커서도_사진과_섞이지_않는다() = withStore { store ->
        store.insertMeta(-1L)
        store.insertMeta(-2L)
        store.insertMeta(7L)
        assertEquals(listOf(-2L, -1L), store.pendingVideos(Store.State.META, 10).map { it.id })
        assertEquals(listOf(-1L), store.pendingVideos(Store.State.META, 10, afterId = -2L).map { it.id })
        assertEquals(listOf(7L), store.pendingPhotos(Store.State.META, 10).map { it.id })
    }
}
