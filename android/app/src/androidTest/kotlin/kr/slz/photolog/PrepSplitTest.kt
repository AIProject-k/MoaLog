package kr.slz.photolog

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.slz.photolog.core.Embeddings
import kr.slz.photolog.ml.ImagePrep
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * 임베딩이 안 맞을 때 **전처리가 다른 건지 모델이 다른 건지**를 가른다.
 *
 * 계약의 `prep_ko.bin`은 데스크톱이 CLIP에 실제로 넣은 224×224 CHW 텐서다. 그걸로:
 *  (a) 그 텐서를 이 기기의 ONNX에 넣어 임베딩 비교 → **모델만의** 차이
 *  (b) 이 기기가 만든 전처리 텐서를 그것과 비교      → **전처리만의** 차이
 *
 * 둘 다 안 재면 세 번째 추측을 하게 된다.
 */
@RunWith(AndroidJUnit4::class)
class PrepSplitTest {

    private val base = File(InstrumentationRegistry.getArguments()
        .getString("dataDir", "/data/local/tmp/photolog"))
    private val fixtures = File(base, "fixtures")
    private val manifest by lazy { JSONObject(File(fixtures, "manifest.json").readText()) }
    private val n by lazy { manifest.getJSONObject("prep").getJSONArray("files").length() }
    private val files by lazy {
        manifest.getJSONObject("prep").getJSONArray("files").let { a -> List(a.length()) { a.getString(it) } }
    }

    /** prep_ko.bin은 (n, 3, 224, 224) float32. 한 장 = 150528 float. */
    private fun desktopTensors(): List<FloatArray> {
        val all = File(fixtures, "prep_ko.bin").readBytes()
        val per = 3 * 224 * 224
        val flat = Embeddings.decode(all, per)
        return (0 until flat.size).map { flat[it] }
    }

    private fun report(s: String) {
        android.util.Log.i("PARITY", s)
        println(s)
    }

    @Test fun a_모델은_같은_텐서에_같은_임베딩을_낸다() {
        val ref = Embeddings.read(File(fixtures, "images_ko.bin"))
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val sess = env.createSession(File(base, "models/vision_model_ko_int8.onnx").absolutePath, opts)

        var worst = 1.0f
        desktopTensors().forEachIndexed { i, chw ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, 224, 224)).use { t ->
                sess.run(mapOf("pixel_values" to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val v = Embeddings.unit((r[0].value as Array<FloatArray>)[0])
                    worst = minOf(worst, Embeddings.dot(ref[i], v))
                }
            }
        }
        sess.close()
        report("[분리] 모델만  : 데스크톱 텐서 → 이 기기 ONNX, 최소 cos %.6f (%d장)".format(worst, n))
        assertTrue("같은 입력에 모델이 다른 답을 낸다 (cos=$worst)", worst > 0.999f)
    }

    @Test fun b_전처리가_데스크톱과_얼마나_다른가() {
        val pp = JSONObject(File(base, "models/preprocessor_config_ko.json").readText())
        val prep = ImagePrep.from(pp)
        val want = desktopTensors()

        var worstCos = 1.0f
        var worstAbs = 0f
        files.forEachIndexed { i, name ->
            val bmp = ImagePrep.decode(File(File(fixtures, "images"), name).absolutePath)!!
            val mine = prep.toChw(bmp)
            bmp.recycle()
            val w = want[i]
            var dot = 0.0; var na = 0.0; var nb = 0.0; var maxAbs = 0f
            for (k in mine.indices) {
                dot += mine[k].toDouble() * w[k]; na += mine[k].toDouble() * mine[k]; nb += w[k].toDouble() * w[k]
                val d = kotlin.math.abs(mine[k] - w[k]); if (d > maxAbs) maxAbs = d
            }
            val cos = (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
            worstCos = minOf(worstCos, cos); worstAbs = maxOf(worstAbs, maxAbs)
        }
        report("[분리] 전처리만: 이 기기 전처리 vs 데스크톱 텐서, 최소 cos %.6f · 최대 픽셀차 %.3f"
            .format(worstCos, worstAbs))
        // 이 테스트는 기준을 강제하지 않는다 — 얼마나 다른지 **재는** 것이 목적이다.
        // 기준은 위 a와 함께 보고 ANDROID.md §15.1에 반영한다.
        assertTrue("전처리가 완전히 다른 그림을 만들고 있다", worstCos > 0.5f)
    }
}
