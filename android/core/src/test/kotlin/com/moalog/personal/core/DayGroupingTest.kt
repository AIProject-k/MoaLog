package com.moalog.personal.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayGroupingTest {
    private val today = LocalDate.of(2026, 9, 30)

    /** 날짜와 일련번호만 있는 항목. 최신순으로 넘긴다. */
    private data class Item(val date: LocalDate?, val n: Int)

    private fun items(vararg specs: Pair<String?, Int>): List<Item> {
        var n = 0
        return specs.flatMap { (d, count) -> List(count) { Item(d?.let(LocalDate::parse), n++) } }
    }

    private fun group(list: List<Item>) = DayGrouping.group(list, { it.date }, today)

    @Test fun `같은 날은 한 묶음이다`() {
        val g = group(items("2026-09-23" to 5))
        assertEquals(1, g.size)
        assertEquals(5, g[0].items.size)
        assertTrue(!g[0].merged)
    }

    @Test fun `3장 이하인 이웃한 날은 묶는다`() {
        val g = group(items("2026-09-25" to 1, "2026-09-24" to 2))
        assertEquals(1, g.size)
        assertEquals(LocalDate.of(2026, 9, 25), g[0].newest)
        assertEquals(LocalDate.of(2026, 9, 24), g[0].oldest)
        assertTrue(g[0].merged)
        assertEquals(3, g[0].items.size)
    }

    @Test fun `4장인 날은 묶지 않는다`() {
        val g = group(items("2026-09-25" to 1, "2026-09-24" to 4))
        assertEquals(2, g.size)
    }

    @Test fun `앞 묶음의 마지막 날이 4장이면 묶지 않는다`() {
        val g = group(items("2026-09-25" to 4, "2026-09-24" to 1))
        assertEquals(2, g.size)
    }

    @Test fun `오늘과 어제는 묶지 않는다`() {
        val g = group(items("2026-09-30" to 1, "2026-09-29" to 1, "2026-09-28" to 1))
        assertEquals(listOf(1, 1, 1), g.map { it.items.size })
    }

    @Test fun `달이 다르면 묶지 않는다`() {
        val g = group(items("2026-09-01" to 1, "2026-08-31" to 1))
        assertEquals(2, g.size)
    }

    @Test fun `사이가 3일을 넘으면 묶지 않는다`() {
        val g = group(items("2026-09-20" to 1, "2026-09-16" to 1))
        assertEquals(2, g.size)
        // 정확히 3일 차이는 묶는다
        assertEquals(1, group(items("2026-09-20" to 1, "2026-09-17" to 1)).size)
    }

    @Test fun `묶음의 가장 오래된 날과 비교해 이어 붙인다`() {
        // 25·23·21일: 25-23은 2일, 23-21도 2일 → 모두 한 묶음. 그런데 25와 21은 4일 떨어졌다.
        val g = group(items("2026-09-25" to 1, "2026-09-23" to 1, "2026-09-21" to 1))
        assertEquals(1, g.size)
        assertEquals(LocalDate.of(2026, 9, 21), g[0].oldest)
    }

    @Test fun `날짜 없는 항목은 맨 뒤에 따로 모은다`() {
        val g = group(items("2026-09-23" to 5, null to 2))
        assertEquals(2, g.size)
        assertNull(g[1].newest)
        assertEquals(2, g[1].items.size)
    }

    @Test fun `날짜 없는 항목은 이웃 날과 묶이지 않는다`() {
        val g = group(items("2026-09-23" to 1, null to 1))
        assertEquals(2, g.size)
    }

    @Test fun `항목이 없으면 묶음도 없다`() {
        assertEquals(emptyList(), group(emptyList()))
    }

    @Test fun `항목 순서는 입력 그대로다`() {
        val g = group(items("2026-09-25" to 1, "2026-09-24" to 1, "2026-09-23" to 1))
        assertEquals(listOf(0, 1, 2), g.single().items.map { it.n })
    }
}
