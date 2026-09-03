package kr.slz.photolog.ml

import android.graphics.Bitmap
import kr.slz.photolog.core.Resampler

/**
 * CLIP 전처리. 데스크톱 `encoder._prep`과 **같은 순서·같은 보간**이어야 한다.
 *
 * 짧은 변을 px(224)로 리사이즈 → 중앙 크롭 → (x/255 − mean)/std → CHW.
 *
 * 보간은 bilinear다 — 데스크톱을 BICUBIC에서 여기에 맞췄다(ANDROID.md §6.2). 다만 안드로이드
 * 기본 `createScaledBitmap`은 2탭이라 PIL과 결과가 다르므로, 실제 리사이즈는 [Resampler]가 한다.
 */
class ImagePrep(
    private val px: Int,
    private val mean: FloatArray,
    private val std: FloatArray,
) {
    /**
     * 반환값은 CHW 순서의 float 배열 (3 × px × px). ONNX 입력에 그대로 들어간다.
     *
     * 순서는 데스크톱 `encoder._prep`과 같다: 짧은 변을 px로 리사이즈(전체 이미지) →
     * 중앙 크롭 → 0~1 → 채널 정규화 → CHW.
     *
     * 리사이즈는 `Bitmap.createScaledBitmap`이 아니라 [Resampler]를 쓴다 — 안드로이드
     * 기본 리사이즈는 2탭이라 축소할 때 픽셀을 버리고, 그 차이만으로 임베딩이 갈렸다.
     */
    fun toChw(src: Bitmap): FloatArray {
        val sw = src.width
        val sh = src.height
        // 목표 크기는 데스크톱과 같은 식으로 정한다(짧은 변 기준, 최소 px).
        val s = px.toFloat() / minOf(sw, sh)
        val dw = maxOf(px, Math.round(sw * s))
        val dh = maxOf(px, Math.round(sh * s))

        val rgb = ByteArray(sw * sh * 3)
        val row = IntArray(sw)
        var o = 0
        for (y in 0 until sh) {
            src.getPixels(row, 0, sw, 0, y, sw, 1)
            for (p in row) {
                rgb[o++] = (p shr 16 and 0xFF).toByte()
                rgb[o++] = (p shr 8 and 0xFF).toByte()
                rgb[o++] = (p and 0xFF).toByte()
            }
        }
        val scaled = Resampler.resizeRgb(rgb, sw, sh, dw, dh)

        val left = (dw - px) / 2
        val top = (dh - px) / 2
        val out = FloatArray(3 * px * px)
        val plane = px * px
        for (y in 0 until px) {
            var p = ((top + y) * dw + left) * 3
            var i = y * px
            for (x in 0 until px) {
                out[i] = (((scaled[p].toInt() and 0xFF) / 255f) - mean[0]) / std[0]
                out[plane + i] = (((scaled[p + 1].toInt() and 0xFF) / 255f) - mean[1]) / std[1]
                out[2 * plane + i] = (((scaled[p + 2].toInt() and 0xFF) / 255f) - mean[2]) / std[2]
                p += 3; i++
            }
        }
        return out
    }

    companion object {
        /**
         * CLIP에 넣을 비트맵을 디코드한다. **알파를 미리 곱하지 않는다.**
         *
         * 안드로이드는 기본으로 알파를 RGB에 미리 곱해서(premultiplied) 준다. PIL의
         * `convert("RGB")`는 알파를 그냥 버리므로, 반투명 픽셀이 있는 PNG에서 두 결과가
         * 갈린다. 갤러리 사진 대부분은 JPEG라 티가 안 나지만 스크린샷·스티커는 PNG다.
         */
        fun decode(path: String): android.graphics.Bitmap? =
            android.graphics.BitmapFactory.decodeFile(path,
                android.graphics.BitmapFactory.Options().apply {
                    inPremultiplied = false
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                })

        /** `preprocessor_config_ko.json`의 값을 그대로 쓴다. 코드에 박지 않는다. */
        fun from(preprocessorJson: org.json.JSONObject): ImagePrep {
            fun arr(k: String) = preprocessorJson.getJSONArray(k)
                .let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            return ImagePrep(
                px = preprocessorJson.getJSONObject("crop_size").getInt("height"),
                mean = arr("image_mean"),
                std = arr("image_std"),
            )
        }
    }
}
