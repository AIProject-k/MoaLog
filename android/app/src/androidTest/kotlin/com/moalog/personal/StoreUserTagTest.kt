package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.Tag
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 사용자가 직접 한 분류 편집이 재분석에서 살아남는지 본다 (외부 검토 · 백업 복원 문제 1).
 *
 * 예전에는 분석이 같은 라벨의 태그를 덮어써서 고정이 풀렸고, 사용자가 뺀 분류가 다시 붙었다.
 */
@RunWith(AndroidJUnit4::class)
class StoreUserTagTest {

    private fun withStore(block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "user-tag-test.db")
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

    private fun Store.addPhoto(id: Long) = writableDatabase.execSQL(
        "INSERT INTO photos (media_id, uri, display_name, is_accessible, state) VALUES (?, ?, ?, 1, ${Store.State.META})",
        arrayOf(id, "content://t/$id", "p$id.jpg"),
    )

    private fun Store.tagSources(id: Long): Map<String, String> = readableDatabase.rawQuery(
        "SELECT label, source FROM tags WHERE media_id=?", arrayOf(id.toString())
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    private val emb = FloatArray(Embeddings.DIM)

    @Test fun 고정한_태그는_재분석해도_사용자_태그로_남는다() = withStore { store ->
        store.addPhoto(1)
        store.pinTag(1, "음식")
        store.saveClip(1, emb, null, null, listOf(Tag("음식", .9f, "clip"), Tag("풍경", .5f, "clip")), "m")
        assertEquals(mapOf("음식" to "user", "풍경" to "clip"), store.tagSources(1))
    }

    @Test fun 사용자가_뺀_분류는_재분석해도_돌아오지_않는다() = withStore { store ->
        store.addPhoto(1)
        store.saveClip(1, emb, null, null, listOf(Tag("풍경", .5f, "clip")), "m")
        store.removeTag(listOf(1L), "풍경")
        store.saveClip(1, emb, null, null, listOf(Tag("풍경", .5f, "clip"), Tag("실내", .4f, "clip")), "m")
        assertEquals(mapOf("실내" to "clip"), store.tagSources(1))
    }

    @Test fun 옮긴_분류는_원래_분류를_빼고_새_분류를_고정한다() = withStore { store ->
        store.addPhoto(1)
        store.saveClip(1, emb, null, null, listOf(Tag("풍경", .5f, "clip")), "m")
        store.moveTag(listOf(1L), from = "풍경", to = "음식")
        store.saveClip(1, emb, null, null, listOf(Tag("풍경", .6f, "clip"), Tag("음식", .3f, "clip")), "m")
        assertEquals(mapOf("음식" to "user"), store.tagSources(1))
    }

    @Test fun 영상_태그를_다시_만들어도_사용자_편집은_유지된다() = withStore { store ->
        store.addPhoto(-1)
        store.pinTag(-1, "음식")
        store.setClipTags(-1, listOf(Tag("음식", .9f, "clip"), Tag("풍경", .5f, "clip")))
        assertEquals(mapOf("음식" to "user", "풍경" to "clip"), store.tagSources(-1))
    }
}
