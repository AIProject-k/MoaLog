package com.moalog.personal

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 장소 검색을 기기에서 확인한다 (계획서 MLG-501 · 외부 검토 문제 1).
 *
 * 화면을 거치지 않고 ViewModel의 검색을 직접 돌린다. adb로는 한글을 입력할 수 없어서다.
 * 전제: DCIM/Camera에 place_gangneung_* 3장(강릉 근처)과 place_busan_* 2장(부산 근처)이 있고, 인덱싱이 끝나 있다.
 */
@RunWith(AndroidJUnit4::class)
class SearchPlaceDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun search(vm: AppViewModel, q: String): AppViewModel.Ui {
        vm.onQuery(q)
        vm.search()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.ui.value
            if (!s.searching && s.parsed != null && s.query == q) return s
            Thread.sleep(200)
        }
        error("검색이 끝나지 않았다: $q")
    }

    @Test fun 강릉_검색은_강릉_반경_안에서_찍은_사진만_낸다() {
        AppGraph.init(app)
        val s = search(AppViewModel(app), "강릉 사진")
        assertEquals(setOf("강릉"), s.parsed?.places)
        assertEquals(3, s.results.size)
    }

    @Test fun 부산_검색은_부산_사진_두_장이다() {
        AppGraph.init(app)
        val s = search(AppViewModel(app), "부산 사진")
        assertEquals(setOf("부산"), s.parsed?.places)
        assertEquals(2, s.results.size)
    }
}
