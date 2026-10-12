package com.moalog.personal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelIdentityTest {

    private val shipped = mapOf("vision.onnx" to "aa11", "text.onnx" to "bb22")

    @Test fun `배포본과 같은 manifest면 예전 신원을 그대로 쓴다`() {
        assertEquals("ko-b32-int8@1234abcd", ModelIdentity.of("ko-b32-int8@1234abcd", shipped, shipped))
    }

    @Test fun `모델이 바뀌면 신원도 바뀐다`() {
        val other = mapOf("vision.onnx" to "cc33", "text.onnx" to "bb22")
        val id = ModelIdentity.of("ko-b32-int8@1234abcd", other, shipped)
        assertNotEquals("ko-b32-int8@1234abcd", id)
        assertTrue(id.startsWith("sha@"))
        assertNotEquals(id, ModelIdentity.of("ko-b32-int8@1234abcd", shipped, shipped))
    }

    @Test fun `같은 내용이면 항상 같은 신원이고 순서는 상관없다`() {
        val a = mapOf("vision.onnx" to "cc33", "text.onnx" to "dd44")
        val b = linkedMapOf("text.onnx" to "dd44", "vision.onnx" to "cc33")
        assertEquals(ModelIdentity.of("x", a, shipped), ModelIdentity.of("x", b, shipped))
    }

    @Test fun `해시 대소문자만 다르면 같은 모델이다`() {
        val upper = mapOf("vision.onnx" to "CC33", "text.onnx" to "dd44")
        val lower = mapOf("vision.onnx" to "cc33", "text.onnx" to "dd44")
        assertEquals(ModelIdentity.of("x", lower, shipped), ModelIdentity.of("x", upper, shipped))
    }

    @Test fun `한 파일이라도 다르면 신원이 다르다`() {
        val a = mapOf("vision.onnx" to "cc33", "text.onnx" to "dd44")
        val b = mapOf("vision.onnx" to "cc33", "text.onnx" to "dd45")
        assertNotEquals(ModelIdentity.of("x", a, shipped), ModelIdentity.of("x", b, shipped))
    }
}
