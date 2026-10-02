package com.moalog.personal.core

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ResamplerCropTest {
    @Test fun centerCropMatchesFullResizeByteForByte() {
        val shapes = listOf(
            320 to 320, 512 to 384, 270 to 585, 180 to 320, 224 to 224,
        )
        for ((sw, sh) in shapes) {
            val scale = 224f / minOf(sw, sh)
            val dw = maxOf(224, Math.round(sw * scale))
            val dh = maxOf(224, Math.round(sh * scale))
            val source = ByteArray(sw * sh * 3) { i -> (i * 73 + i / 131 * 29).toByte() }
            val full = Resampler.resizeRgb(source, sw, sh, dw, dh)
            val expected = ByteArray(224 * 224 * 3)
            val left = (dw - 224) / 2
            val top = (dh - 224) / 2
            for (y in 0 until 224) {
                full.copyInto(expected, y * 224 * 3, ((top + y) * dw + left) * 3,
                    ((top + y) * dw + left + 224) * 3)
            }
            assertContentEquals(expected,
                Resampler.resizeRgbCenterCrop(source, sw, sh, dw, dh, 224), "$sw x $sh")
        }
    }
}
