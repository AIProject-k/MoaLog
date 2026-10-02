package com.moalog.personal.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 시간 말 풀이. 앱이 예시로 내건 "작년 여름 바다"가 실제로 풀려야 한다. */
class QueryParserTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = QueryParser(zone = zone)
    private val now = LocalDate.of(2026, 9, 28)

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toEpochSecond()
    private fun endOf(y: Int, m: Int, d: Int) = day(y, m, d) + 86_399

    @Test fun `작년 여름은 작년의 6~8월이다`() {
        val p = parser.parse("작년 여름 바다", now)
        assertEquals(day(2025, 6, 1), p.from)
        assertEquals(endOf(2025, 8, 31), p.until)
        assertEquals("바다", p.text)
    }

    @Test fun `연도 말이 뒤에 와도 같다`() {
        val p = parser.parse("여름 작년 바다", now)
        assertEquals(day(2025, 6, 1), p.from)
    }

    @Test fun `2024년 3월`() {
        val p = parser.parse("2024년 3월 벚꽃", now)
        assertEquals(day(2024, 3, 1), p.from)
        assertEquals(endOf(2024, 3, 31), p.until)
    }

    @Test fun `연도 없는 계절은 가장 최근에 지나간 계절이다`() {
        // 9월 말에 "겨울" = 다가올 겨울이 아니라 지난 겨울(2025-12 ~ 2026-02)
        val p = parser.parse("겨울 눈", now)
        assertEquals(day(2025, 12, 1), p.from)
        assertEquals(endOf(2026, 2, 28), p.until)
        val summer = parser.parse("여름", now)
        assertEquals(day(2026, 6, 1), summer.from)
    }

    private val tagged = QueryParser(knownTags = setOf("강아지", "물/바다"), zone = zone)

    @Test fun `영상 말은 종류 칩이 되고 검색어에서 빠진다`() {
        val p = tagged.parse("강아지가 나오는 영상", now)
        assertEquals("video", p.media)
        assertEquals(setOf("강아지"), p.tags)
        assertEquals("", p.text)
        assertEquals(listOf("태그: 강아지", "종류: 동영상"), p.chips)
    }

    @Test fun `사진 말은 사진만 찾는다`() {
        assertEquals("image", tagged.parse("바다 사진", now).media)
    }

    @Test fun `끈 태그 칩은 그 말이 검색어로 돌아간다`() {
        val p = tagged.parse("작년 강아지", now, disabled = setOf("태그: 강아지"))
        assertEquals(emptySet(), p.tags)
        assertEquals("강아지", p.text)
        assertEquals(listOf("시간: 작년", "태그: 강아지"), p.chips)        // 칩은 남아 있어야 다시 켤 수 있다
    }

    @Test fun `끈 시간 칩은 조건에서 빠지고 검색어에도 안 간다`() {
        val p = parser.parse("작년 여름 바다", now, disabled = setOf("시간: 작년"))
        assertEquals("바다", p.text)
        // 작년을 껐으니 '여름'은 올해 여름이다
        assertEquals(day(2026, 6, 1), p.from)
    }

    @Test fun `끈 종류 칩은 필터를 풀고 검색어에도 안 간다`() {
        val p = tagged.parse("강아지 영상", now, disabled = setOf("종류: 동영상"))
        assertEquals(null, p.media)
        assertEquals("", p.text)
    }

    @Test fun `연도 없는 미래 달은 작년 그 달이다`() {
        val p = parser.parse("12월 트리", now)
        assertEquals(day(2025, 12, 1), p.from)
        assertNotNull(p.until)
    }
}
