package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.core.TimeSource
import com.moalog.personal.data.MediaRow
import com.moalog.personal.data.MetaResult
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StoreMediaQueueTest {
    @Test fun requeueVideosTouchesOnlyVideosAndClearsTheirFrames() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "requeue-test.db")
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            store.upsertDiscovered(listOf(-3L, -2L, -1L, 5L).map { id ->
                MediaRow(id, "content://test/$id", null, null, null, null,
                    if (id < 0) "video/mp4" else "image/jpeg", 100, 10, 10, 0, 1, 0)
            })
            for (id in listOf(-3L, -2L, 5L)) {
                store.saveMeta(id, MetaResult(0, TimeSource.FILE, null, null, null), emptyList())
                store.saveClip(id, FloatArray(512) { 1f }, null, null, emptyList(), "m")
            }
            store.markFailed(-1)
            val unit = FloatArray(512) { 1f / Math.sqrt(512.0).toFloat() }
            store.saveFrame(-3, 10_000, unit)

            assertEquals(2, store.countsVideos().done)
            assertEquals(3, store.requeueVideos())          // 완료 2개 + 실패 1개

            assertEquals(0, store.countsVideos().done)
            assertEquals(setOf(-3L, -2L), store.pendingVideos(Store.State.META, 10).map { it.id }.toSet())
            assertEquals(listOf(-1L), store.pendingVideos(Store.State.NEW, 10).map { it.id })
            assertEquals("사진은 그대로다", 1, store.countsPhotos().done)
            // 예전 방식으로 뽑은 프레임이 새 결과에 섞이지 않는다
            assertTrue(store.frames(-3).isEmpty())
            assertTrue(store.searchVideoFrames(unit, null).isEmpty())
            // 부분적으로 다시 분석된 영상은 프레임이 생기는 대로 검색된다
            store.saveFrame(-3, 5_000, unit)
            val hit = store.searchVideoFrames(unit, null).getValue(-3L)
            assertEquals(5_000L, hit.first)
            assertTrue(hit.second > 0.999f)
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }

    @Test fun setClipTagsReplacesOnlyClipTags() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "retag-test.db")
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            store.upsertDiscovered(listOf(-1L).map { id ->
                MediaRow(id, "content://test/$id", null, null, null, null, "video/mp4", 100, 10, 10, 0, 1, 0)
            })
            store.saveMeta(-1, MetaResult(0, TimeSource.FILE, null, null, null),
                listOf(com.moalog.personal.core.Tag("카메라촬영", null, "rule")))
            store.saveClip(-1, FloatArray(512) { 1f }, null, null,
                listOf(com.moalog.personal.core.Tag("바다", .9f, "clip"), com.moalog.personal.core.Tag("셀카", .5f, "clip")), "m")
            store.pinTag(-1, "여행")                                   // 사용자가 고정한 태그
            store.saveFrame(-1, 0, FloatArray(512) { 1f })

            assertEquals(listOf(-1L), store.videoIdsWithFrames())
            store.setClipTags(-1, listOf(com.moalog.personal.core.Tag("바다", .9f, "clip")))

            val labels = store.tagsOf(-1).map { it.label to it.source }.toSet()
            assertEquals(setOf("카메라촬영" to "rule", "바다" to "clip", "여행" to "user"), labels)
            assertEquals("프레임은 그대로다", setOf(0L), store.frames(-1).keys)
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }

    @Test fun photoAndVideoQueuesAndCountsStaySeparate() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "media-queue-test.db")
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            store.upsertDiscovered(listOf(-2L, -1L, 7L, 8L).map { id ->
                MediaRow(id, "content://test/$id", null, null, null, null,
                    if (id < 0) "video/mp4" else "image/jpeg", 100, 10, 10, 0, 1, 0)
            })
            assertEquals(listOf(7L), store.pendingPhotos(Store.State.NEW, 1).map { it.id })
            assertEquals(listOf(-2L), store.pendingVideos(Store.State.NEW, 1).map { it.id })
            assertEquals(setOf(7L, 8L), store.generations(video = false).keys)
            assertEquals(setOf(-2L, -1L), store.generations(video = true).keys)
            assertEquals(2, store.countsPhotos().total)
            assertEquals(2, store.countsVideos().total)

            store.saveMeta(7, MetaResult(0, TimeSource.FILE, null, null, null), emptyList())
            store.saveMeta(-2, MetaResult(0, TimeSource.FILE, null, null, null), emptyList())
            store.saveClip(7, FloatArray(512), 0.0, 0.0, emptyList(), "test")
            assertEquals(listOf(8L), store.pendingPhotos(Store.State.NEW, 10).map { it.id })
            assertEquals(listOf(-1L), store.pendingVideos(Store.State.NEW, 10).map { it.id })
            assertEquals(1, store.countsPhotos().done)
            assertEquals(0, store.countsVideos().done)

            assertEquals(0, store.deleteMissing(setOf(7, 8), video = false))
            assertEquals(2, store.countsVideos().total)
            assertEquals(2, store.deleteMissing(emptySet(), video = true))
            assertEquals(2, store.countsPhotos().total)
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }
}
