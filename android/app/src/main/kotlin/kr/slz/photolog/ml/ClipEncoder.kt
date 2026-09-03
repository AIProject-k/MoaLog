package kr.slz.photolog.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import kr.slz.photolog.core.BpeTokenizer
import kr.slz.photolog.core.Embeddings
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * CLIP ONNX 래퍼. 데스크톱 `encoder.py`와 **같은 .onnx 파일**을 돌린다.
 *
 * 세션은 필요할 때만 연다(ANDROID.md §7.1). vision 96MB + text 64MB를 동시에 들고 있을
 * 이유가 없다. 모델은 **경로로** 연다 — 96MB를 ByteArray로 자바 힙에 올리면 OOM이다.
 *
 * 실행 프로바이더는 CPU 고정이다. 동적 양자화의 MatMulInteger를 실제로 돌리는 유일한
 * EP이고, NNAPI는 Android 15부터 deprecated다(§7.2).
 */
class ClipEncoder(
    private val visionPath: File,
    private val textPath: File,
    private val tokenizer: BpeTokenizer,
    private val prep: ImagePrep,
    private val threads: Int = 4,
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var vision: OrtSession? = null
    private var text: OrtSession? = null

    private fun options() = OrtSession.SessionOptions().apply {
        // intra-op은 빅코어 수에 맞춘다. 리틀코어를 섞으면 느린 코어가 배리어를 잡는다.
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    fun openVision() { if (vision == null) vision = env.createSession(visionPath.absolutePath, options()) }
    fun openText() { if (text == null) text = env.createSession(textPath.absolutePath, options()) }

    fun closeVision() { vision?.close(); vision = null }
    fun closeText() { text?.close(); text = null }

    /**
     * 사진 한 장 → 512차원 단위벡터.
     *
     * **한 장씩 넣는다.** 배치로 넣으면 int8 동적 양자화의 활성값 스케일이 배치 전체에서
     * 나와, 같이 넣은 다른 사진이 이 사진의 임베딩을 바꾼다 — 데스크톱 실측으로 스캔 순서만
     * 섞어도 400장 중 11장의 1순위 라벨이 뒤집혔고 같은 사진의 코사인이 0.9435까지
     * 내려갔다(dupe_similarity 0.95와 같은 크기). 자세한 근거는 ANDROID.md §7.5.
     */
    fun encodeImage(bitmap: Bitmap): FloatArray {
        openVision()
        val chw = prep.toChw(bitmap)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, PX, PX)).use { t ->
            vision!!.run(mapOf("pixel_values" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return Embeddings.unit((r[0].value as Array<FloatArray>)[0])
            }
        }
    }

    /** 문장 → 512차원 단위벡터. 토크나이저는 config에 적힌 규칙을 따른다(§7.3). */
    fun encodeText(s: String): FloatArray {
        openText()
        val ids = tokenizer.encodePadded(s)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
            text!!.run(mapOf("input_ids" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return Embeddings.unit((r[0].value as Array<FloatArray>)[0])
            }
        }
    }

    override fun close() { closeVision(); closeText() }

    companion object {
        const val PX = 224L
    }
}
