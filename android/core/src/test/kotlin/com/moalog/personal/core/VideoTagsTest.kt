package com.moalog.personal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 프레임 투표. 합집합이 만든 "영상당 태그 9개"가 다시 생기지 않게 한다. */
class VideoTagsTest {
    // 프레임 하나의 분류 결과. 첫 항목이 1순위 대분류다(Classifier.classify의 규약).
    private fun frame(primary: String, vararg more: String, score: Float = .5f) =
        listOf(Tag(primary, score, "clip")) + more.map { Tag(it, .3f, "clip") }

    private fun labels(tags: List<Tag>) = tags.map { it.label }

    @Test fun `한두 프레임에만 나온 라벨은 영상의 태그가 아니다`() {
        // 7프레임: 바다 7번, 클로즈업 3번(43%), 셀카 2번, 지도화면 1번
        val frames = List(7) { i ->
            frame("바다", *listOfNotNull(
                "클로즈업".takeIf { i < 3 }, "셀카".takeIf { i in 3..4 }, "지도화면".takeIf { i == 6 },
            ).toTypedArray())
        }
        assertEquals(listOf("바다", "클로즈업"), labels(VideoTags.aggregate(frames)))
    }

    @Test fun `프레임이 하나뿐이면 그 사진의 태그 전부다`() {
        val one = listOf(frame("음식", "클로즈업", "실내"))
        assertEquals(setOf("음식", "클로즈업", "실내"), labels(VideoTags.aggregate(one)).toSet())
    }

    @Test fun `프레임이 셋이면 둘 이상에서 나와야 한다`() {
        val frames = listOf(frame("풍경", "셀카"), frame("풍경", "실외"), frame("풍경"))
        assertEquals(listOf("풍경"), labels(VideoTags.aggregate(frames)))
    }

    @Test fun `통과한 대분류가 없으면 가장 자주 1순위였던 대분류를 붙인다`() {
        // 여섯 프레임이 모두 다른 장면 — 어느 것도 40%에 못 미친다. 그래도 영상이 태그 없이 남으면 안 된다.
        val frames = listOf(
            frame("음식", score = .4f), frame("풍경", score = .9f), frame("문서", score = .5f),
            frame("반려동물", score = .6f), frame("인물", score = .7f), frame("풍경", score = .8f),
        )
        // 풍경이 두 번으로 가장 많다(33% < 40%지만 1순위 득표 최다)
        assertEquals(listOf("풍경"), labels(VideoTags.aggregate(frames)))
    }

    @Test fun `대분류가 미분류뿐이면 미분류를 붙이고 속성은 남는다`() {
        val frames = List(5) { frame(Classifier.UNCLASSIFIED, "클로즈업") }
        val out = labels(VideoTags.aggregate(frames))
        assertTrue(Classifier.UNCLASSIFIED in out, out.toString())
        assertTrue("클로즈업" in out, out.toString())
    }

    @Test fun `미분류는 다른 대분류가 이기면 빠진다`() {
        val frames = List(5) { i -> if (i < 4) frame("음식") else frame(Classifier.UNCLASSIFIED) }
        assertEquals(listOf("음식"), labels(VideoTags.aggregate(frames)))
    }

    @Test fun `태그는 득표가 많은 순으로 상한까지만 남긴다`() {
        val frames = List(10) { frame("A", "B", "C", "D", "E", "F", "G") }
        val out = VideoTags.aggregate(frames, maxTags = 3)
        assertEquals(3, out.size)
        assertEquals("A", out.first().label)     // 1순위 대분류가 동률에서 앞선다(득표 같으면 점수순)
    }

    @Test fun `점수는 가장 잘 맞은 프레임의 값이다`() {
        val frames = listOf(frame("바다", score = .4f), frame("바다", score = .9f), frame("바다", score = .6f))
        assertEquals(.9f, VideoTags.aggregate(frames).single().score)
    }

    @Test fun `프레임이 없으면 태그도 없다`() {
        assertTrue(VideoTags.aggregate(emptyList()).isEmpty())
    }

    @Test fun `규칙 도장은 기준값에서 나온다`() {
        assertEquals("vote0.4-max5", VideoTags.RULE)
    }
}
