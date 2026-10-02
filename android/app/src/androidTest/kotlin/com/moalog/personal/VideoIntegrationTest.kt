package com.moalog.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.moalog.personal.work.IndexWorker
import com.moalog.personal.ui.mediaUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the test emulator, with MoaLogVideoTest.mp4 seeded in Movies. */
@RunWith(AndroidJUnit4::class)
class VideoIntegrationTest {
    @org.junit.Rule @JvmField
    val videoPermission = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_VIDEO)

    @Test fun indexAndSearchLocalVideo() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AppGraph.init(ctx)
        AppGraph.media.sync()
        val db = AppGraph.store.readableDatabase
        val id = db.rawQuery("SELECT media_id FROM photos WHERE display_name='MoaLogVideoTest.mp4'", null).use {
            assertTrue("Seed video must be visible to the app", it.moveToFirst()); it.getLong(0)
        }
        assertTrue(id < 0)
        assertEquals(AppGraph.store.photo(id)!!.uri, mediaUri(id))
        assertTrue("On-device CLIP assets must be ready before indexing", AppGraph.modelsReady)
        IndexWorker.start(ctx)
        val deadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < deadline) {
            val jobs = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(IndexWorker.NAME).get()
            if (jobs.isNotEmpty() && jobs.all { it.state.isFinished }) {
                assertTrue("Index worker failed", jobs.any { it.state == WorkInfo.State.SUCCEEDED }); break
            }
            Thread.sleep(500)
        }
        val frames = AppGraph.store.frames(id)
        val state = db.rawQuery("SELECT state FROM photos WHERE media_id=?", arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        assertTrue("Expected multiple timestamped scenes (frames=${frames.size}, state=$state, modelsReady=${AppGraph.modelsReady})", frames.size >= 2)
        val first = frames.entries.first()
        val results = AppGraph.store.searchVideoFrames(first.value, setOf(id))
        assertTrue(results.getValue(id).second > .99f)
        val album = AppGraph.store.createUserAlbum("영상 테스트 모음")
        AppGraph.store.addToUserAlbum(album, listOf(id))
        assertEquals(id, AppGraph.store.userAlbumPhotos(album, 10, 0).single().id)
        AppGraph.store.deleteUserAlbum(album)
        val before = frames.keys
        AppGraph.media.sync()
        assertEquals(before, AppGraph.store.frames(id).keys)
    }
}
