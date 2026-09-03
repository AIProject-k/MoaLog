package kr.slz.photolog.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * float32 리틀엔디언 원시 임베딩 읽기/쓰기.
 *
 * .npy를 쓰지 않는 이유: 헤더 파싱이 필요해서 폰 쪽에 공짜가 아니다. 모양은
 * manifest에 적혀 있으므로 파일은 숫자만 담으면 된다. 512차원 float32 = 2KB/장,
 * 1만 장이면 20MB — 검색 인덱스를 통째로 메모리에 들고 있어도 되는 크기다(§10.1).
 */
object Embeddings {
    const val DIM = 512

    fun read(file: File, dim: Int = DIM): Array<FloatArray> {
        val bytes = file.readBytes()
        require(bytes.size % (dim * 4) == 0) {
            "${file.name}: ${bytes.size}바이트는 ${dim}차원 float32로 나누어떨어지지 않는다"
        }
        return decode(bytes, dim)
    }

    fun decode(bytes: ByteArray, dim: Int = DIM): Array<FloatArray> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(bytes.size / (dim * 4)) { FloatArray(dim).also { buf.get(it) } }
    }

    fun encode(vec: FloatArray): ByteArray =
        ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { asFloatBuffer().put(vec) }.array()

    /** L2 정규화. 이렇게 저장해 두면 코사인 유사도가 단순 내적이 된다. */
    fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        val inv = (1.0 / maxOf(Math.sqrt(n), 1e-12)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
