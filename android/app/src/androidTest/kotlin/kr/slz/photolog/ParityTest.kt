package kr.slz.photolog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.slz.photolog.core.BpeTokenizer
import kr.slz.photolog.core.Classifier
import kr.slz.photolog.core.Embeddings
import kr.slz.photolog.core.PromptMatrix
import kr.slz.photolog.ml.ClipEncoder
import kr.slz.photolog.ml.ImagePrep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * **Phase 0 관문** (ANDROID.md §15.2).
 *
 * 데스크톱이 만든 계약(`eval/fixtures/`)을 폰에서 그대로 재현하는지 본다.
 * 통과하면 데스크톱에서 잰 정확도 0.86이 이 기기의 정확도다.
 * 실패하면 폰이 다른 모델을 돌리고 있는 것이고, 그건 에러가 아니라 '그럴듯한 오답'으로 나온다.
 *
 * 자산은 APK에 넣지 않는다 — 모델만 160MB다. adb로 앱 외부 파일 디렉터리에 밀어 넣는다:
 *   adb push models/  /data/local/tmp/photolog/models/   (onnx 2개 + preprocessor json)
 *   adb push eval/fixtures     /data/local/tmp/photolog/fixtures
 *   adb shell chmod -R 755 /data/local/tmp/photolog
 * 위치는 `-e dataDir <경로>`로 바꿀 수 있다.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ParityTest {

    companion object {
        private lateinit var base: File
        private lateinit var fixtures: File
        private lateinit var manifest: JSONObject
        private lateinit var config: JSONObject
        private lateinit var tokenizer: BpeTokenizer
        private lateinit var enc: ClipEncoder
        private val report = StringBuilder()

        @BeforeClass @JvmStatic fun setUp() {
            // 앱 외부 디렉터리(/sdcard/Android/data/...)는 adb push가 만든 파일을
            // 앱이 못 읽는다(EACCES). /data/local/tmp는 0771이라 앱이 traverse할 수 있고,
            // 그 안의 world-readable 파일은 열린다 — 테스트 자산을 넣는 표준 자리다.
            base = File(InstrumentationRegistry.getArguments()
                .getString("dataDir", "/data/local/tmp/photolog"))
            fixtures = File(base, "fixtures")
            assertTrue("계약이 없다: $fixtures — adb push 를 먼저 할 것", fixtures.isDirectory)

            manifest = JSONObject(File(fixtures, "manifest.json").readText())
            config = JSONObject(File(base, "config.json").readText())
            tokenizer = BpeTokenizer.fromDir(File(fixtures, "tokenizer"))

            val models = File(base, "models")
            val pp = JSONObject(File(models, "preprocessor_config_ko.json").readText())
            enc = ClipEncoder(
                visionPath = File(models, "vision_model_ko_int8.onnx"),
                textPath = File(models, "text_model_ko_int8.onnx"),
                tokenizer = tokenizer,
                prep = ImagePrep.from(pp),
            )
            report.append("\n========== Phase 0 패리티 ==========\n")
            report.append("기기 ${android.os.Build.MODEL} · ABI ${android.os.Build.SUPPORTED_ABIS[0]}")
                .append(" · API ${android.os.Build.VERSION.SDK_INT}\n")
        }

        @AfterClass @JvmStatic fun tearDown() {
            if (::enc.isInitialized) enc.close()
            report.append("====================================\n")
            android.util.Log.i("PARITY", report.toString())
            println(report)
        }

        private fun rss(): Long {
            val d = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(d)
            return d.totalPss * 1024L
        }
    }

    /** 1. 토크나이저: 정수라 '거의 같다'가 없다. 하나만 달라도 다른 문장이다. */
    @Test fun t1_토크나이저가_305문장을_정확히_재현한다() {
        val arr = JSONArray(File(fixtures, "tokens_ko.json").readText())
        var bad = 0
        var first: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val want = o.getJSONArray("ids").let { a -> List(a.length()) { a.getInt(it) } }
            val got = tokenizer.encode(o.getString("text"))
            if (got != want) {
                bad++
                if (first == null) first = "${o.getString("text").take(30)} 기대=$want 실제=$got"
            }
        }
        report.append("토큰       ${arr.length() - bad}/${arr.length()} 일치\n")
        assertEquals("불일치 ${bad}개. 첫 사례: $first", 0, bad)
    }

    /** 2. 텍스트 타워: 같은 토큰을 넣어 같은 벡터가 나오는가. */
    @Test fun t2_텍스트_임베딩이_데스크톱과_같다() {
        val texts = JSONObject(File(fixtures, "texts_ko.json").readText())
            .getJSONArray("texts").let { a -> List(a.length()) { a.getString(it) } }
        val ref = Embeddings.read(File(fixtures, "texts_ko.bin"))
        assertEquals(ref.size, texts.size)

        val t0 = System.nanoTime()
        val cos = DoubleArray(texts.size) { Embeddings.dot(ref[it], enc.encodeText(texts[it])).toDouble() }
        val ms = (System.nanoTime() - t0) / 1e6 / texts.size
        enc.closeText()

        cos.sort()
        report.append("텍스트 임베딩  중앙 %.6f  최소 %.6f  (%.1f ms/문장)\n"
            .format(cos[cos.size / 2], cos[0], ms))
        val min = manifest.getJSONObject("criteria").getDouble("cos_min")
        assertTrue("텍스트 코사인 최소 ${cos[0]} < $min", cos[0] >= min)
    }

    /** 3. 비전 타워 + 안드로이드 전처리. 여기가 진짜 관문이다. */
    @Test fun t3_이미지_임베딩과_분류가_데스크톱과_같다() {
        val meta = JSONArray(File(fixtures, "images_ko.json").readText())
        val ref = Embeddings.read(File(fixtures, "images_ko.bin"))
        assertEquals(ref.size, meta.length())

        val before = rss()
        val cos = DoubleArray(meta.length())
        val got = arrayOfNulls<FloatArray>(meta.length())
        var totalNs = 0L
        for (i in 0 until meta.length()) {
            val f = File(File(fixtures, "images"), meta.getJSONObject(i).getString("file"))
            val bmp = ImagePrep.decode(f.absolutePath)
                ?: throw AssertionError("디코드 실패: ${f.name}")
            val t0 = System.nanoTime()
            val v = enc.encodeImage(bmp)
            totalNs += System.nanoTime() - t0
            bmp.recycle()
            got[i] = v
            cos[i] = Embeddings.dot(ref[i], v).toDouble()
        }
        val peak = rss()
        val msPer = totalNs / 1e6 / meta.length()

        // 분류까지 붙여 본다 — 사용자가 실제로 보는 것은 코사인이 아니라 라벨이다.
        val suffix = if (manifest.getString("model_variant") == "ko") "_ko" else ""
        fun pm(kind: String): PromptMatrix {
            val m = manifest.getJSONObject("prompts").getJSONObject(kind)
            val labels = m.getJSONArray("labels").let { a -> List(a.length()) { a.getString(it) } }
            return PromptMatrix(labels, Embeddings.read(File(fixtures, "prompts_$kind.bin")))
        }
        val clf = Classifier(pm("categories"), pm("attributes"),
                             Classifier.Thresholds.from(config, suffix))
        var same = 0
        val misses = StringBuilder()
        for (i in 0 until meta.length()) {
            val want = meta.getJSONObject(i).getString("top1")
            val mine = clf.top1(got[i]!!)
            if (want == mine) same++
            else if (misses.length < 300)
                misses.append("\n    ${meta.getJSONObject(i).getString("file").take(34)}: 기대 $want 실제 $mine")
        }

        val sorted = cos.clone().also { it.sort() }
        report.append("이미지 임베딩  중앙 %.6f  최소 %.6f\n".format(sorted[sorted.size / 2], sorted[0]))
        // 가장 안 맞는 사진을 찍어 둔다 — 남은 차이가 디코드에서 오는지 보려면 파일을 봐야 한다.
        cos.indices.sortedBy { cos[it] }.take(4).forEach {
            report.append("    낮음 %.4f  %s\n".format(cos[it], meta.getJSONObject(it).getString("file").take(40)))
        }
        report.append("1순위 라벨    $same/${meta.length()} 일치$misses\n")
        report.append("속도         %.1f ms/장  (1만 장 %.1f분)\n".format(msPer, msPer * 10000 / 60000))
        report.append("메모리        PSS %.0f → %.0f MB\n".format(before / 1e6, peak / 1e6))

        val minCos = manifest.getJSONObject("criteria").getDouble("cos_min")
        val minLabel = manifest.getJSONObject("criteria").getString("label_min").substringBefore("/").toInt()
        assertTrue("이미지 코사인 최소 ${sorted[0]} < $minCos", sorted[0] >= minCos)
        assertTrue("1순위 라벨 일치 $same < $minLabel", same >= minLabel)
    }

    /** 4. 배치를 쓰지 않는 이유를 이 기기에서도 확인한다 (§7.5). */
    @Test fun t4_임베딩이_사진만의_함수다() {
        val meta = JSONArray(File(fixtures, "images_ko.json").readText())
        val f = File(File(fixtures, "images"), meta.getJSONObject(0).getString("file"))
        val bmp = ImagePrep.decode(f.absolutePath)
        val a = enc.encodeImage(bmp!!)
        // 사이에 다른 사진을 여러 장 넣어도 같은 벡터가 나와야 한다
        for (i in 1..4) {
            val other = File(File(fixtures, "images"), meta.getJSONObject(i).getString("file"))
            ImagePrep.decode(other.absolutePath)!!.also { enc.encodeImage(it); it.recycle() }
        }
        val b = enc.encodeImage(bmp)
        bmp.recycle()
        val c = Embeddings.dot(a, b)
        report.append("결정성        같은 사진 재인코딩 cos %.6f\n".format(c))
        assertTrue("다른 사진을 거치고 나니 벡터가 바뀌었다 (cos=$c)", c > 0.9999f)
    }
}
