package com.moalog.personal.core

import kotlin.test.*

class VideoSamplingTest {
    @Test fun invalidDuration() { assertTrue(VideoSampling.positions(0).isEmpty()) }
    @Test fun shortClip() { assertEquals(listOf(500L), VideoSampling.positions(1000)) }

    @Test fun `짧은 영상은 5초마다 본다`() {
        val samples = VideoSampling.positions(60_000)
        assertEquals(13, samples.size)
        assertTrue(samples.zipWithNext().all { (a, b) -> b - a <= VideoSampling.STEP_MS })
    }

    @Test fun `긴 영상은 상한 안에서 고르게 나눈다`() {
        val samples = VideoSampling.positions(3_600_000)
        assertEquals(VideoSampling.MAX_FRAMES, samples.size)
        assertEquals(0L, samples.first())
        assertEquals(3_599_999L, samples.last())
        assertTrue(samples.zipWithNext().all { (a, b) -> a < b })
        // 예전 24장(2분 30초 간격)보다 훨씬 촘촘해야 한다
        assertTrue(samples.zipWithNext().maxOf { (a, b) -> b - a } < 40_000)
    }

    @Test fun `샘플링 도장은 간격과 상한에서 나온다`() {
        assertEquals("5000x96-prev-sync", VideoSampling.STAMP)
    }

    @Test fun `시각 표기`() {
        assertEquals("0:05", VideoSampling.time(5_400))
        assertEquals("2:18", VideoSampling.time(138_000))
        assertEquals("1:02:03", VideoSampling.time(3_723_000))
    }

    @Test fun `붙어 있는 같은 장면은 하나로 친다`() {
        val hits = listOf(10_000L to .30f, 15_000L to .31f, 20_000L to .29f, 90_000L to .25f, 200_000L to .10f)
        val top = VideoSampling.topScenes(hits, limit = 3)
        assertEquals(listOf(15_000L, 90_000L, 200_000L), top.map { it.first })
    }
}

class VideoMetaTest {
    @Test fun `컨테이너 시각은 UTC다`() {
        val t = VideoMeta.parseDate("20260925T123456.000Z")
        assertEquals(java.time.LocalDateTime.of(2026, 9, 25, 12, 34, 56).toEpochSecond(java.time.ZoneOffset.UTC), t)
    }

    @Test fun `MP4 기준 시각과 빈 값은 모르는 것으로 본다`() {
        assertNull(VideoMeta.parseDate("19040101T000000.000Z"))
        assertNull(VideoMeta.parseDate("19700101T000000.000Z"))
        assertNull(VideoMeta.parseDate(""))
        assertNull(VideoMeta.parseDate(null))
        assertNull(VideoMeta.parseDate("20261399T000000.000Z"))
    }

    @Test fun `ISO 6709 위치`() {
        assertEquals(37.5665 to 126.978, VideoMeta.parseLocation("+37.5665+126.9780/"))
        assertEquals(-33.8688 to 151.2093, VideoMeta.parseLocation("-33.8688+151.2093+012.000/"))
        assertNull(VideoMeta.parseLocation("+00.0000+000.0000/"))
        assertNull(VideoMeta.parseLocation("garbage"))
    }
}
