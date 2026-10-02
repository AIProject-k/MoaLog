package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.data.MediaRow
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StoreGroupingTest {
    @Test fun forgettingPhotoMarksDerivedGroupsOutdated() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "grouping-test.db")
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            store.upsertDiscovered(listOf(MediaRow(1, "content://test/1", null, null,
                null, null, "image/jpeg", 100, 10, 10, 0, 1, 0)))
            store.metaPut("grouping_complete", "1")
            store.forget(listOf(1))
            assertEquals("0", store.metaGet("grouping_complete"))
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }
}
