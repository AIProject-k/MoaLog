package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.data.PhotoSort
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 사진 탭 정렬. 촬영일·추가일·용량 순서가 맞고, 값이 없는 사진은 맨 뒤로 가고,
 * 쪽을 나눠 읽어도 같은 사진이 두 번 나오거나 빠지지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class StoreSortTest {

    private fun withStore(block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "sort-test-${System.nanoTime()}.db")
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try { block(store) } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }

    private fun Store.add(id: Long, bytes: Long, taken: Long?, added: Long?) = writableDatabase.execSQL(
        "INSERT INTO photos (media_id, uri, display_name, bytes, taken_at, date_added, rel_path, is_accessible, state) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'DCIM/Camera/', 1, ${Store.State.META})",
        arrayOf<Any?>(id, "content://t/$id", "IMG_$id.jpg", bytes, taken, added),
    )

    private fun Store.ids(sort: PhotoSort): List<Long> = photos(100, 0, "all", sort).map { it.id }

    @Test fun 촬영일순은_촬영일이_최신인_순이고_촬영일_없는_사진은_맨_뒤다() = withStore { store ->
        store.add(1, bytes = 10, taken = 100, added = 5)
        store.add(2, bytes = 30, taken = 300, added = 1)
        store.add(3, bytes = 20, taken = null, added = 9)
        assertEquals(listOf(2L, 1L, 3L), store.ids(PhotoSort.DATE))
    }

    @Test fun 추가일순은_새로_들어온_순이고_촬영일과_무관하다() = withStore { store ->
        store.add(1, bytes = 10, taken = 100, added = 5)
        store.add(2, bytes = 30, taken = 300, added = 1)
        store.add(3, bytes = 20, taken = null, added = 9)
        assertEquals(listOf(3L, 1L, 2L), store.ids(PhotoSort.ADDED))
    }

    @Test fun 추가일을_모르는_옛_사진은_추가일순에서_맨_뒤다() = withStore { store ->
        store.add(1, bytes = 10, taken = 100, added = 5)
        store.add(4, bytes = 40, taken = 400, added = null)
        assertEquals(listOf(1L, 4L), store.ids(PhotoSort.ADDED))
    }

    @Test fun 용량순은_큰_파일부터() = withStore { store ->
        store.add(1, bytes = 10, taken = 100, added = 5)
        store.add(2, bytes = 30, taken = 300, added = 1)
        store.add(3, bytes = 20, taken = null, added = 9)
        assertEquals(listOf(2L, 3L, 1L), store.ids(PhotoSort.SIZE))
    }

    /** 값이 같은 사진이 많아도 쪽을 넘기며 읽으면 전부 한 번씩만 나와야 한다. 정렬 값이 같을 때 media_id로 고정한다. */
    @Test fun 같은_값이_많아도_쪽을_나눠_읽으면_중복과_누락이_없다() = withStore { store ->
        for (id in 1L..10L) store.add(id, bytes = 500, taken = 1_000, added = 1_000)
        for (sort in PhotoSort.entries) {
            val pages = (0 until 10 step 3).flatMap { offset ->
                store.photos(3, offset, "all", sort).map { it.id }
            }
            assertEquals("$sort 쪽 나눔", (1L..10L).toList().sortedDescending(), pages.sortedDescending())
            assertEquals("$sort 중복", pages.size, pages.toSet().size)
        }
    }

    @Test fun 추가일_채우기는_비어_있는_사진만_채운다() = withStore { store ->
        store.add(1, bytes = 10, taken = 100, added = 5)
        store.add(4, bytes = 40, taken = 400, added = null)
        fun addedOf(id: Long): Long? = store.readableDatabase.rawQuery(
            "SELECT date_added FROM photos WHERE media_id=?", arrayOf(id.toString()),
        ).use { c -> c.moveToFirst(); if (c.isNull(0)) null else c.getLong(0) }

        store.fillDateAdded(mapOf(1L to 99L, 4L to 7L))
        assertEquals("이미 있던 값은 그대로", 5L, addedOf(1))
        assertEquals("비어 있던 값은 채운다", 7L, addedOf(4))
        store.fillDateAdded(mapOf(4L to 50L))
        assertEquals("이미 채운 값은 다시 덮지 않는다", 7L, addedOf(4))
    }
}
