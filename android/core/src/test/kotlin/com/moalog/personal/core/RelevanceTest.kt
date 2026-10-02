package com.moalog.personal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RelevanceTest {
    @Test fun `결과가 없으면 0이다`() = assertEquals(0, Relevance.closeCount(emptyList()))

    @Test fun `1등과 차이가 윈도 안인 것만 가깝다`() {
        val scores = listOf(.30f, .29f, .27f, .24f, .20f)
        assertEquals(3, Relevance.closeCount(scores, window = .04f))
    }

    @Test fun `1등은 늘 가깝다`() {
        assertEquals(1, Relevance.closeCount(listOf(.30f, .10f, .09f)))
    }

    @Test fun `점수가 몰려 있으면 상한에서 자른다`() {
        val flat = List(100) { .30f - it * 0.0001f }
        assertEquals(Relevance.MAX_CLOSE, Relevance.closeCount(flat))
    }
}
