package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.data.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Upgrades a disposable v0.2.0 database, never the installed app database. */
@RunWith(AndroidJUnit4::class)
class StoreMigrationTest {
    @Test fun v2ToCurrentPreservesPhotoAndUserRecords() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "migration-${System.nanoTime()}.db")
        val embedding = byteArrayOf(1, 2, 3, 4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { old ->
            old.execSQL("""CREATE TABLE photos (
                media_id INTEGER PRIMARY KEY, uri TEXT NOT NULL, display_name TEXT,
                bucket TEXT, rel_path TEXT, owner_pkg TEXT, mime TEXT, bytes INTEGER,
                width INTEGER, height INTEGER, orientation INTEGER, generation INTEGER,
                date_modified INTEGER, taken_at INTEGER, taken_at_source TEXT,
                gps_lat REAL, gps_lon REAL, camera_model TEXT, blur_score REAL,
                brightness REAL, sha256 TEXT, embedding BLOB, model_id TEXT,
                state INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
            old.execSQL("CREATE TABLE tags (media_id INTEGER, label TEXT, score REAL, source TEXT, PRIMARY KEY(media_id,label))")
            old.execSQL("CREATE TABLE favorites (media_id INTEGER PRIMARY KEY, ts INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE user_albums (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE user_album_photos (album_id INTEGER NOT NULL, media_id INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(album_id,media_id))")
            old.execSQL("CREATE TABLE user_feedback (media_id INTEGER NOT NULL, label TEXT NOT NULL, verdict INTEGER NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(media_id,label))")
            old.execSQL("INSERT INTO photos(media_id,uri,display_name,state,embedding) VALUES(11,'content://old/11','old.jpg',2,?)", arrayOf(embedding))
            old.execSQL("INSERT INTO tags VALUES(11,'바다',0.8,'clip')")
            old.execSQL("INSERT INTO favorites VALUES(11,123)")
            old.execSQL("INSERT INTO user_albums VALUES(5,'내 여행',100)")
            old.execSQL("INSERT INTO user_album_photos VALUES(5,11,101)")
            old.execSQL("INSERT INTO user_feedback VALUES(11,'바다',1,102)")
            old.version = 2
        }
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            val db = store.readableDatabase
            assertEquals(9, db.version)
            db.rawQuery("SELECT uri,state,embedding,media_kind,is_accessible FROM photos WHERE media_id=11", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("content://old/11", c.getString(0))
                assertEquals(Store.State.DONE, c.getInt(1))
                assertArrayEquals(embedding, c.getBlob(2))
                assertEquals("photo", c.getString(3))
                // 업그레이드 직후 사진이 사라져 보이면 안 된다 (다음 동기화 전까지 빈 갤러리)
                assertEquals(1, c.getInt(4))
            }
            fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            for (table in listOf("tags", "favorites", "user_albums", "user_album_photos", "user_feedback"))
                assertEquals("Lost $table", 1, count(table))
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM pragma_table_info('user_album_photos') WHERE name='scene_time_ms'", null).use { c -> c.moveToFirst(); c.getInt(0) })
            // v9: 분석 실패 횟수 열이 생기고 기존 행은 0이다
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM pragma_table_info('photos') WHERE name='fail_count'", null).use { c -> c.moveToFirst(); c.getInt(0) })
            assertEquals(0, db.rawQuery("SELECT fail_count FROM photos WHERE media_id=11", null).use { c -> c.moveToFirst(); c.getInt(0) })
            // 예전 영상의 촬영 시각을 한 번 다시 읽도록 표시돼 있어야 한다
            assertEquals("1", store.metaGet("video_capture_backfill"))
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }
}
