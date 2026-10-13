package com.moalog.personal.core

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 모델 파일 무결성 (계획서 MLG-103). 앱에 든 ONNX가 manifest의 SHA-256과 같은지 본다.
 *
 * 해시는 복사와 **같은 순회**에서 계산한다 — 160MB를 한 번 더 읽지 않는다.
 *
 * 모델 **신원**(`AppGraph.modelId`)은 여기서 만들지 않는다. 신원을 해시로 바꾸면 기존 사용자의
 * 임베딩이 전부 무효가 되어 1만 장을 다시 분석해야 한다(`Store.checkModel`). 이 파일은
 * "파일이 깨졌나"만 본다.
 */
object ModelIntegrity {

    /** manifest.json의 `files`: 파일 이름 → SHA-256(소문자 16진수). 항목이 없으면 빈 맵. */
    fun parseManifest(json: String): Map<String, String> {
        val files = JSONObject(json).optJSONObject("files") ?: return emptyMap()
        return files.keys().asSequence().associateWith { files.getString(it).lowercase() }
    }

    /** manifest.json의 `sizes`: 파일 이름 → 바이트 수. 시작할 때 값싸게 확인하려는 것이다. 없으면 빈 맵. */
    fun parseSizes(json: String): Map<String, Long> {
        val sizes = JSONObject(json).optJSONObject("sizes") ?: return emptyMap()
        return sizes.keys().asSequence().associateWith { sizes.getLong(it) }
    }

    /** [input]을 [output]으로 복사하면서 SHA-256을 계산한다. [output]이 null이면 읽기만 한다. */
    fun copyAndHash(input: InputStream, output: OutputStream?, bufferSize: Int = 1 shl 16): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output?.write(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(input: InputStream): String = copyAndHash(input, null)

    /** 두 해시가 같은가. 대소문자는 무시하고, 한쪽이 없으면 같지 않다. */
    fun same(actual: String?, expected: String?): Boolean =
        actual != null && expected != null && actual.equals(expected, ignoreCase = true)
}
