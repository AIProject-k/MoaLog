package com.moalog.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.moalog.personal.work.IndexWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 장소 표가 새로 생기거나 바뀌었을 때, 업데이트한 기존 사용자의 이벤트에도 장소 이름이 붙는지 본다
 * (외부 검토 · 이벤트 장소명 문제 2).
 *
 * 전제: 에뮬레이터에 장소 이름이 붙은 이벤트가 이미 있다(강릉·부산 근처 사진 인덱싱 뒤).
 * 이벤트의 장소 이름을 지우고 도장을 비워 "예전 버전에서 올라온" 상태를 만든 뒤 워커를 돌린다.
 * 새 사진도, 변경도 없으므로 도장 비교만이 이벤트를 다시 만들게 한다.
 */
@RunWith(AndroidJUnit4::class)
class PlaceRefreshDeviceTest {

    @Test fun 장소_표_도장이_다르면_기존_이벤트에_장소_이름이_다시_붙는다() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AppGraph.init(ctx)
        val store = AppGraph.store
        val db = store.writableDatabase
        fun named() = db.rawQuery("SELECT COUNT(*) FROM events WHERE place_name IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) }

        val before = named()
        assertTrue("전제: 장소 이름이 붙은 이벤트가 있어야 한다", before > 0)
        db.execSQL("UPDATE events SET place_name=NULL")
        assertEquals(0, named())
        store.metaPut("place_index_stamp", "")

        IndexWorker.start(ctx)
        val deadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < deadline) {
            val jobs = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(IndexWorker.NAME).get()
            if (jobs.isNotEmpty() && jobs.all { it.state.isFinished }) {
                assertTrue("워커가 실패했다", jobs.any { it.state == WorkInfo.State.SUCCEEDED }); break
            }
            Thread.sleep(500)
        }
        assertEquals("재분석 없이 이벤트 장소 이름이 돌아와야 한다", before, named())
        assertEquals(AppGraph.placeStamp, store.metaGet("place_index_stamp"))
    }
}
