package com.moalog.personal.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ProseTest {
    private val wj = '\u2060'

    @Test
    fun `끝 두 글자 사이에 붙임 문자를 넣는다`() {
        assertEquals("합니${wj}다", "합니다".keepTail())
    }

    @Test
    fun `끝의 마침표는 건너뛰고 그 앞 두 글자를 붙인다`() {
        assertEquals("있습니${wj}다.", "있습니다.".keepTail())
        assertEquals("적어보세${wj}요.", "적어보세요.".keepTail())
    }

    @Test
    fun `마지막 낱말이 한 글자면 그대로 둔다`() {
        assertEquals("이게 뭐 야", "이게 뭐 야".keepTail())
        assertEquals("가", "가".keepTail())
    }

    @Test
    fun `빈 글과 부호뿐인 글은 그대로 둔다`() {
        assertEquals("", "".keepTail())
        assertEquals("...", "...".keepTail())
    }
}
