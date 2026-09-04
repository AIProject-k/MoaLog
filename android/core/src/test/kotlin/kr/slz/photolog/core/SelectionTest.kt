package kr.slz.photolog.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionTest {
    @Test fun `사진을 다시 누르면 선택이 해제된다`() {
        assertEquals(setOf(1L), Selection.toggle(setOf(1L, 2L), 2L))
    }

    @Test fun `전체 선택은 모두 고르고 다시 누르면 해제한다`() {
        val ids = setOf(1L, 2L, 3L)
        assertEquals(ids, Selection.toggleAll(emptySet(), ids))
        assertEquals(emptySet(), Selection.toggleAll(ids, ids))
    }
}
