package com.moalog.personal

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.TimeSource
import com.moalog.personal.data.MediaIndex
import com.moalog.personal.data.MediaKind
import com.moalog.personal.data.MetaResult
import com.moalog.personal.data.Store
import com.moalog.personal.ml.ClipEncoder
import com.moalog.personal.ml.ImagePrep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the device video path using only a generated, temporary MP4. */
@RunWith(AndroidJUnit4::class)
class DeviceVideoSmokeTest {
    @Test fun extractSearchAndPreparePlayback() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver
        val dbFile = File(ctx.cacheDir, "video-smoke-${System.nanoTime()}.db")
        val isolatedCtx = object : ContextWrapper(ctx) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(isolatedCtx)
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "MoaLogDeviceSmoke-${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MoaLogTest/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("Cannot insert test video")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("MoaLogDeviceSmoke.mp4")
                .use { source -> resolver.openOutputStream(uri)!!.use(source::copyTo) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val id = -ContentUris.parseId(uri)
            val syncStart = SystemClock.elapsedRealtimeNanos()
            MediaIndex(ctx, store).syncVideos()
            val syncMs = (SystemClock.elapsedRealtimeNanos() - syncStart) / 1e6
            val item = store.pendingVideos(Store.State.NEW, 100).firstOrNull { it.id == id }
            assertNotNull("Synthetic video missing from MediaStore sync", item)
            assertEquals(MediaKind.VIDEO, item!!.mediaKind)
            assertTrue("MediaStore duration missing", (item.durationMs ?: 0L) >= 3_000L)
            store.saveMeta(id, MetaResult(0, TimeSource.FILE, null, null, null), emptyList())

            val retriever = MediaMetadataRetriever()
            var decodeMs = 0.0
            var encodeMs = 0.0
            var visionOpenMs = 0.0
            val vectors = try {
                retriever.setDataSource(ctx, uri)
                AppGraph.init(ctx)
                assertTrue("Bundled CLIP model missing", AppGraph.modelsReady)
                val config = JSONObject(ctx.assets.open("models/preprocessor_config_ko.json")
                    .bufferedReader().use { it.readText() })
                val encoder = ClipEncoder(
                    File(ctx.noBackupFilesDir, "models/vision_model_ko_int8.onnx"),
                    File(ctx.noBackupFilesDir, "models/text_model_ko_int8.onnx"),
                    AppGraph.tokenizer, ImagePrep.from(config), 4,
                )
                try {
                    val openStart = SystemClock.elapsedRealtimeNanos()
                    encoder.openVision()
                    visionOpenMs = (SystemClock.elapsedRealtimeNanos() - openStart) / 1e6
                    listOf(500L, 2_500L).map { time ->
                        val decodeStart = SystemClock.elapsedRealtimeNanos()
                        val frame = retriever.getScaledFrameAtTime(time * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                        decodeMs += (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1e6
                        assertNotNull("Frame missing at $time ms", frame)
                        try {
                            val encodeStart = SystemClock.elapsedRealtimeNanos()
                            encoder.encodeImage(frame!!).also {
                                encodeMs += (SystemClock.elapsedRealtimeNanos() - encodeStart) / 1e6
                            }
                        } finally { frame!!.recycle() }
                    }
                } finally { encoder.close() }
            } finally { retriever.release() }
            assertTrue("Scenes produced identical embeddings", Embeddings.dot(vectors[0], vectors[1]) < .999f)
            store.saveFrame(id, 500L, vectors[0])
            store.saveFrame(id, 2_500L, vectors[1])
            store.saveClip(id, vectors[0], 0.0, 0.0, emptyList(), "device-smoke")
            val hit = store.searchVideoFrames(vectors[1], setOf(id)).getValue(id)
            assertEquals(2_500L, hit.first)
            assertTrue(hit.second > .999f)

            val player = MediaPlayer()
            try {
                val prepareStart = SystemClock.elapsedRealtimeNanos()
                player.setDataSource(ctx, uri)
                player.prepare()
                val prepareMs = (SystemClock.elapsedRealtimeNanos() - prepareStart) / 1e6
                assertTrue("MediaPlayer duration missing", player.duration >= 3_000)
                player.seekTo(hit.first.toInt())
                Log.i("DeviceVideoSmoke", "sync_ms=%.1f vision_open_ms=%.1f decode_2_frames_ms=%.1f encode_2_frames_ms=%.1f player_prepare_ms=%.1f".format(
                    syncMs, visionOpenMs, decodeMs, encodeMs, prepareMs))
            } finally { player.release() }
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
            resolver.delete(uri, null, null)
        }
    }
}
