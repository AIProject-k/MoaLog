package com.moalog.personal

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.moalog.personal.data.Store
import com.moalog.personal.work.IndexPhase
import com.moalog.personal.work.IndexWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the video test emulator, which contains MoaLogVideoTest.mp4. */
@RunWith(AndroidJUnit4::class)
class PhotoVideoOrderTest {
    @org.junit.Rule @JvmField
    val mediaPermission = GrantPermissionRule.grant(
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
    )

    @Test fun photosFinishBeforeVideoFramesStart() {
        assumeTrue("Runs only on an Android emulator", android.os.Build.FINGERPRINT.contains("sdk_gphone"))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AppGraph.init(ctx)
        val resolver = ctx.contentResolver
        val videoId = resolver.query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME}=?",
            arrayOf("MoaLogVideoTest.mp4"), null)!!.use { c ->
            if (c.moveToFirst()) -c.getLong(0) else null
        }
        assumeTrue("Video test seed is absent", videoId != null)

        val created = ArrayList<android.net.Uri>()
        try {
            repeat(12) { i ->
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "MoaLogOrderTest-$i.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MoaLogTest/")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }) ?: error("Could not create test photo")
                created.add(uri)
                val bitmap = Bitmap.createBitmap(384, 512, Bitmap.Config.ARGB_8888)
                try {
                    val pixels = IntArray(384 * 512) { p ->
                        0xff000000.toInt() or (((p + i * 19) and 255) shl 16) or
                            ((((p / 384) * 3 + i * 31) and 255) shl 8) or ((p * 7 + i) and 255)
                    }
                    bitmap.setPixels(pixels, 0, 384, 0, 0, 384, 512)
                    resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                } finally { bitmap.recycle() }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val ids = created.map(ContentUris::parseId)
            AppGraph.media.sync()
            AppGraph.store.requeueClip()
            IndexWorker.restart(ctx)

            var sawPhotos = false
            var sawVideos = false
            var completed = false
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                val jobs = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(IndexWorker.NAME).get()
                val active = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: jobs.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                when (active?.progress?.getString(IndexWorker.KEY_PHASE)) {
                    IndexPhase.PHOTOS.name -> {
                        sawPhotos = true
                        assertTrue("Video frames began during photo processing", AppGraph.store.frames(videoId!!).isEmpty())
                    }
                    IndexPhase.VIDEOS.name -> {
                        sawVideos = true
                        assertTrue("Video phase began before photo phase", sawPhotos)
                        assertEquals(ids.size, photoDoneCount(ids))
                    }
                }
                if (sawVideos && active == null && jobs.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    completed = true
                    break
                }
                Thread.sleep(100)
            }
            assertTrue("Photo phase not observed", sawPhotos)
            assertTrue("Video phase not observed", sawVideos)
            assertTrue("Worker did not complete", completed)
            assertTrue("Video frames missing", AppGraph.store.frames(videoId!!).size >= 2)
        } finally {
            created.forEach { resolver.delete(it, null, null) }
            AppGraph.store.forget(created.map(ContentUris::parseId))
        }
    }

    private fun photoDoneCount(ids: List<Long>): Int = AppGraph.store.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM photos WHERE state=${Store.State.DONE} AND media_id IN (${ids.joinToString(",")})",
        null,
    ).use { c -> c.moveToFirst(); c.getInt(0) }
}
