package com.moalog.personal.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelIntegrityTest {

    private val payload = ByteArray(200_000) { (it * 31 + 7).toByte() }

    @Test fun `SHA-256 표준 벡터와 맞는다`() {
        val abc = ModelIntegrity.sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc)
    }

    @Test fun `복사하면서 해시를 계산하고 내용은 그대로 옮긴다`() {
        val out = ByteArrayOutputStream()
        val hash = ModelIntegrity.copyAndHash(ByteArrayInputStream(payload), out, bufferSize = 4096)
        assertTrue(out.toByteArray().contentEquals(payload), "복사 결과가 원본과 다르다")
        assertEquals(ModelIntegrity.sha256(ByteArrayInputStream(payload)), hash)
    }

    @Test fun `한 바이트만 깨져도 해시가 달라진다`() {
        val good = ModelIntegrity.sha256(ByteArrayInputStream(payload))
        val flipped = payload.copyOf().also { it[123_456] = (it[123_456] + 1).toByte() }
        assertFalse(ModelIntegrity.same(ModelIntegrity.sha256(ByteArrayInputStream(flipped)), good))
    }

    @Test fun `읽는 도중 깨진 복사본은 원본 해시와 다르다`() {
        // 복사 경로 한가운데서 바이트를 뒤집는 입력. 디스크에 쓰기 전에 잡혀야 한다.
        val corrupting = object : FilterInputStream(ByteArrayInputStream(payload)) {
            private var pos = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0 && pos <= 50_000 && pos + n > 50_000) b[off + (50_000 - pos)] = 0
                if (n > 0) pos += n
                return n
            }
        }
        val expected = ModelIntegrity.sha256(ByteArrayInputStream(payload))
        val actual = ModelIntegrity.copyAndHash(corrupting, null)
        assertFalse(ModelIntegrity.same(actual, expected))
    }

    @Test fun `manifest의 files를 읽고 해시는 소문자로 맞춘다`() {
        val json = """{"schema_version":1,"files":{"a.onnx":"ABCDEF","b.onnx":"0123"}}"""
        assertEquals(mapOf("a.onnx" to "abcdef", "b.onnx" to "0123"), ModelIntegrity.parseManifest(json))
    }

    @Test fun `files가 없는 manifest는 빈 맵이다`() {
        assertTrue(ModelIntegrity.parseManifest("""{"schema_version":1}""").isEmpty())
    }

    @Test fun `해시 비교는 대소문자를 무시하고 없는 쪽은 틀린 것으로 본다`() {
        assertTrue(ModelIntegrity.same("ABCDEF", "abcdef"))
        assertFalse(ModelIntegrity.same(null, "abcdef"))
        assertFalse(ModelIntegrity.same("abcdef", null))
    }
}
