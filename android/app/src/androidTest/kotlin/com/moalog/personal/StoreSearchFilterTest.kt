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
 * 검색 필터의 조회 상한을 없앤 것을 못박는다(계획서 MLG-101, 결함 D-1·D-2).
 *
 * 예전에는 날짜 조건이 `recent(20000)`으로 최신 2만 장만 보고, 태그 조건이 `photosByTag(t, 5000)`으로
 * 5천 장에서 잘랐다. 그래서 큰 갤러리에서는 오래된 사진이 조건과 무관하게 빠졌다.
 * 여기서는 상한을 넘는 데이터를 넣고, 가장 오래된 사진과 5천 장을 넘는 태그가 빠지지 않는지 본다.
 */
@RunWith(AndroidJUnit4::class)
class StoreSearchFilterTest {

    private fun withStore(block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "search-filter-test.db")
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

    /** 분석이 끝난 사진 한 줄을 직접 넣는다. 상한 테스트는 수만 장이라 Store의 저장 경로를 거치면 느리다. */
    private fun Store.insertPhotos(rows: Sequence<Pair<Long, Long?>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO photos (media_id, uri, is_accessible, state, taken_at) VALUES (?, ?, 1, 2, ?)"
            )
            for ((id, takenAt) in rows) {
                stmt.bindLong(1, id)
                stmt.bindString(2, "content://test/$id")
                if (takenAt == null) stmt.bindNull(3) else stmt.bindLong(3, takenAt)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Test fun 날짜_조건은_최신_2만장_밖의_옛날_사진도_찾는다() = withStore { store ->
        // 1번은 1990년(가장 오래됨), 나머지 25,000장은 2020년대. 예전 코드는 1번을 못 봤다.
        store.insertPhotos(sequence {
            yield(1L to 631_152_000L)
            for (i in 2L..25_001L) yield(i to 1_700_000_000L + i)
        })
        val old = store.takenBetween(from = 0L, until = 700_000_000L)
        assertEquals(listOf(1L), old.map { it.first })
    }

    @Test fun 태그_조건은_5천장을_넘어도_전부_센다() = withStore { store ->
        store.insertPhotos(sequence { for (i in 1L..5_200L) yield(i to i) })
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val tag = db.compileStatement("INSERT INTO tags (media_id, label, score, source) VALUES (?, '바다', 0.9, 'clip')")
            for (i in 1L..5_200L) { tag.bindLong(1, i); tag.executeInsert() }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        assertEquals(5_200, store.idsByTag("바다").size)
    }

    @Test fun 최신순_목록은_촬영_시각이_없는_것을_뒤로_보낸다() = withStore { store ->
        store.insertPhotos(sequence {
            yield(1L to 100L)
            yield(2L to null)
            yield(3L to 300L)
        })
        assertEquals(listOf(3L, 1L, 2L), store.idsNewestFirst())
    }

    @Test fun 기간_조건은_양쪽_끝을_포함한다() = withStore { store ->
        store.insertPhotos(sequence {
            yield(1L to 100L)
            yield(2L to 200L)
            yield(3L to 300L)
        })
        assertEquals(setOf(2L, 3L), store.takenBetween(from = 200L, until = null).map { it.first }.toSet())
        assertEquals(setOf(1L, 2L), store.takenBetween(from = null, until = 200L).map { it.first }.toSet())
    }
}
