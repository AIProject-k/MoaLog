package kr.slz.photolog.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupScreenTest {
    @Test
    fun `사진 권한이 없으면 권한 안내 화면을 표시한다`() {
        assertEquals(Screen.Permission, startupScreen(Access.None, Screen.Home))
    }

    @Test
    fun `사진 권한이 있으면 현재 화면을 유지한다`() {
        assertEquals(Screen.Search, startupScreen(Access.Full, Screen.Search))
    }
}
