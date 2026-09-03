package kr.slz.photolog.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * PIL 호환 이중선형 리샘플러 (RGB 8비트, 분리형 2패스).
 *
 * 안드로이드 `Bitmap.createScaledBitmap(filter=true)`은 **2탭** 이중선형이다. 원본
 * 픽셀 4개만 보고 만들기 때문에 크게 줄이면 그 사이 픽셀이 통째로 버려진다.
 * PIL의 `Image.BILINEAR`은 축소 배율만큼 필터 지지폭을 늘려 그 구간을 평균낸다.
 *
 * 실측(기기, 사진 8장): 안드로이드 기본 리사이즈로 만든 CLIP 입력 텐서는 데스크톱
 * 텐서와 **코사인 0.9448, 최대 픽셀차 2.34**까지 벌어졌고, 그것만으로 임베딩 코사인이
 * 0.905까지 내려가고 64장 중 3장의 1순위 라벨이 뒤집혔다. 같은 텐서를 넣으면 모델
 * 임베딩은 코사인 1.000000으로 완전히 같았다 — 차이는 전부 여기였다.
 *
 * 폰을 데스크톱에 맞추는 게 아니라 **양쪽 다 제대로 된 필터를 쓰게** 하는 방향이다.
 * 에일리어싱을 남기는 쪽에 맞추면 숫자는 맞고 품질은 잃는다.
 *
 * ponytail: 삼각(=이중선형) 필터만 구현한다. Lanczos·bicubic이 필요해지면 그때
 *           `filter`를 갈아 끼우면 되지만, tokenizer config가 BILINEAR을 말하는 한 불필요하다.
 */
object Resampler {

    /**
     * @param src RGB 인터리브 바이트 (길이 = w*h*3), 채널당 0~255
     * @return 같은 형식의 dw*dh*3 바이트
     */
    fun resizeRgb(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        require(src.size == sw * sh * 3) { "입력 크기가 ${sw}x${sh}x3과 다르다: ${src.size}" }
        if (sw == dw && sh == dh) return src
        // 가로 → 세로. PIL도 두 패스를 각각 8비트로 마무리하므로 중간 반올림까지 맞춘다.
        val horiz = pass(src, sw, sh, dw, sh, horizontal = true)
        return pass(horiz, dw, sh, dw, dh, horizontal = false)
    }

    private fun pass(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int, horizontal: Boolean): ByteArray {
        val inSize = if (horizontal) sw else sh
        val outSize = if (horizontal) dw else dh
        if (inSize == outSize) return src
        val (bounds, weights, maxK) = coeffs(inSize, outSize)
        val out = ByteArray(dw * dh * 3)

        if (horizontal) {
            for (y in 0 until sh) {
                val rowIn = y * sw * 3
                val rowOut = y * dw * 3
                for (x in 0 until dw) {
                    val min = bounds[x * 2]; val n = bounds[x * 2 + 1]
                    var r = 0f; var g = 0f; var b = 0f
                    for (k in 0 until n) {
                        val w = weights[x * maxK + k]
                        val p = rowIn + (min + k) * 3
                        r += w * (src[p].toInt() and 0xFF)
                        g += w * (src[p + 1].toInt() and 0xFF)
                        b += w * (src[p + 2].toInt() and 0xFF)
                    }
                    val o = rowOut + x * 3
                    out[o] = clip8(r); out[o + 1] = clip8(g); out[o + 2] = clip8(b)
                }
            }
        } else {
            for (y in 0 until dh) {
                val min = bounds[y * 2]; val n = bounds[y * 2 + 1]
                val rowOut = y * dw * 3
                for (x in 0 until dw) {
                    var r = 0f; var g = 0f; var b = 0f
                    for (k in 0 until n) {
                        val w = weights[y * maxK + k]
                        val p = (min + k) * sw * 3 + x * 3
                        r += w * (src[p].toInt() and 0xFF)
                        g += w * (src[p + 1].toInt() and 0xFF)
                        b += w * (src[p + 2].toInt() and 0xFF)
                    }
                    val o = rowOut + x * 3
                    out[o] = clip8(r); out[o + 1] = clip8(g); out[o + 2] = clip8(b)
                }
            }
        }
        return out
    }

    /**
     * PIL `precompute_coeffs`와 같은 계수. 핵심은 **축소할 때 지지폭을 배율만큼 넓히는 것**
     * (`filterscale`) — 이것이 없으면 버려지는 픽셀이 생긴다.
     */
    private fun coeffs(inSize: Int, outSize: Int): Triple<IntArray, FloatArray, Int> {
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = 1.0 * filterScale                 // 삼각 필터의 지지 반경은 1.0
        val maxK = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val weights = FloatArray(outSize * maxK)

        for (i in 0 until outSize) {
            val center = (i + 0.5) * scale
            var min = floor(center - support + 0.5).toInt().coerceAtLeast(0)
            var max = floor(center + support + 0.5).toInt().coerceAtMost(inSize)
            if (max <= min) { min = center.toInt().coerceIn(0, inSize - 1); max = min + 1 }
            val n = max - min
            var sum = 0f
            for (k in 0 until n) {
                val t = ((min + k) - center + 0.5) / filterScale
                val w = triangle(t)
                weights[i * maxK + k] = w
                sum += w
            }
            if (sum != 0f) for (k in 0 until n) weights[i * maxK + k] /= sum
            bounds[i * 2] = min
            bounds[i * 2 + 1] = n
        }
        return Triple(bounds, weights, maxK)
    }

    private fun triangle(x: Double): Float {
        val a = if (x < 0) -x else x
        return if (a < 1.0) (1.0 - a).toFloat() else 0f
    }

    private fun clip8(v: Float): Byte {
        val i = v.roundToInt()
        return when { i < 0 -> 0; i > 255 -> 255.toByte(); else -> i.toByte() }
    }
}
