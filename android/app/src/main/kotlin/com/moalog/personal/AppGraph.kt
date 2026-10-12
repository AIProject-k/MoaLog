package com.moalog.personal

import android.content.Context
import com.moalog.personal.core.BpeTokenizer
import com.moalog.personal.core.Classifier
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.ModelIntegrity
import com.moalog.personal.core.Personalize
import com.moalog.personal.core.PromptMatrix
import com.moalog.personal.core.QueryParser
import com.moalog.personal.core.RuleTagger
import com.moalog.personal.core.SearchIndex
import com.moalog.personal.data.MediaIndex
import com.moalog.personal.data.Store
import com.moalog.personal.ml.ClipEncoder
import com.moalog.personal.ml.ImagePrep
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 수동 주입. Hilt를 쓰지 않는다 — 1인 프로젝트에 애노테이션 처리와 빌드 시간만 붙는다.
 *
 * 무거운 것은 `by lazy`다. 특히 [encoder]는 세션을 열면 160MB를 쓰므로 실제로 필요할
 * 때까지 만들지 않는다(§7.1).
 */
object AppGraph {

    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    val store: Store by lazy { Store(app) }
    val media: MediaIndex by lazy { MediaIndex(app, store) }

    /**
     * 데스크톱과 **같은 config.json**. 프롬프트·임계값·출처 규칙이 전부 여기 있고
     * 코드에는 상수가 없다 — 튜닝값이 코드에 박히면 데스크톱에서 잰 것이 무의미해진다.
     */
    val config: JSONObject by lazy {
        JSONObject(app.assets.open("config.json").bufferedReader().use { it.readText() })
    }

    val variantSuffix: String by lazy {
        if (config.optString("model_variant", "ko") == "ko") "_ko" else ""
    }

    val ruleTagger: RuleTagger by lazy { RuleTagger.from(config) }

    /**
     * 모델 파일. ORT는 경로가 필요하고 assets는 실제 파일이 아니라 한 번 복사한다.
     * `noBackupFilesDir`에 두는 이유: 파생 데이터이므로 백업에 넣을 이유가 없다(§9).
     */
    private val modelDir: File by lazy { File(app.noBackupFilesDir, "models").apply { mkdirs() } }

    /**
     * 모델 준비 결과. null이면 정상, 아니면 사용자에게 보일 이유다.
     * 예전에는 실패하면 `false`만 남아서 CLIP 분석이 조용히 건너뛰어졌다. 이제 이유를 남긴다 (계획서 MLG-103).
     */
    private val modelCheck: String? by lazy {
        runCatching { prepareModels() }.getOrElse { "AI 모델을 준비하지 못했습니다: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** 모델을 쓸 수 없는 이유. 정상이면 null. */
    val modelError: String? get() = modelCheck

    val modelsReady: Boolean get() = modelCheck == null

    /**
     * assets → noBackupFilesDir 복사. 복사하는 동안 SHA-256을 확인하고, 디스크에 쓴 뒤에도 다시 읽어 확인한다.
     * 확인에 실패하면 불완전한 파일을 남기지 않고 이유를 돌려준다.
     */
    private fun prepareModels(): String? {
        val dir = modelDir
        val manifestText = app.assets.open("models/manifest.json").bufferedReader().use { it.readText() }
        val expected = ModelIntegrity.parseManifest(manifestText)
        // 앱 버전이 그대로여도 manifest가 바뀌면 다시 복사한다 — 모델을 갈아 끼운 경우다.
        val stamp = app.packageManager.getPackageInfo(app.packageName, 0).let {
            (if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else it.versionName ?: "0") +
                ":" + ModelIntegrity.sha256(manifestText.byteInputStream())
        }
        if (store.metaGet(KEY_MODEL_STAMP) == stamp && ONNX_ASSETS.all { File(dir, it).length() > 0 }) return null

        for (name in ONNX_ASSETS) {
            val want = expected[name] ?: return "모델 목록(manifest)에 $name 이(가) 없습니다. 앱을 다시 설치해 주세요."
            val part = File(dir, "$name.part")
            val got = try {
                app.assets.open("models/$name").use { input ->
                    part.outputStream().use { ModelIntegrity.copyAndHash(input, it) }
                }
            } catch (e: java.io.IOException) {
                part.delete()
                return "모델 $name 을(를) 기기에 복사하지 못했습니다. 저장 공간을 확인해 주세요."
            }
            if (!ModelIntegrity.same(got, want)) {
                part.delete()
                return "모델 $name 이(가) 손상되었습니다. 앱을 다시 설치해 주세요."
            }
            val target = File(dir, name)
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            // 쓰기 도중 깨졌을 수 있으니 디스크에서 다시 읽는다. 복사할 때만 치르는 한 번짜리 비용이다.
            val onDisk = target.inputStream().use { ModelIntegrity.sha256(it) }
            if (!ModelIntegrity.same(onDisk, want)) {
                target.delete()
                return "모델 $name 을(를) 저장한 뒤 확인하지 못했습니다. 저장 공간과 기기 상태를 확인해 주세요."
            }
        }
        store.metaPut(KEY_MODEL_STAMP, stamp)
        return null
    }

    /** 전처리 설정은 파일로 만들 필요가 없다 — JSON으로 읽으면 끝이다. ORT만 경로를 요구한다. */
    private val preprocessorJson: JSONObject by lazy {
        JSONObject(app.assets.open("models/preprocessor_config_ko.json").bufferedReader().use { it.readText() })
    }

    /**
     * 모델 신원. 파일 내용으로 만들어 두면 모델을 바꿨을 때 기존 임베딩이 자동
     * 무효화된다 — 다른 공간의 벡터를 비교하면 검색과 중복 판정이 엉킨다(§14).
     */
    val modelId: String by lazy {
        modelCheck // 복사가 끝나야 파일 길이가 의미를 가진다. 안 그러면 0으로 찍혀 신원이 틀어진다.
        val md = MessageDigest.getInstance("SHA-256")
        for (name in ONNX_ASSETS) md.update(File(modelDir, name).length().toString().toByteArray())
        md.update(config.optString("model_variant", "ko").toByteArray())
        "ko-b32-int8@" + md.digest().take(4).joinToString("") { "%02x".format(it) }
    }

    val tokenizer: BpeTokenizer by lazy {
        BpeTokenizer.build(
            app.assets.open("tokenizer/config.json").bufferedReader().use { it.readText() },
            app.assets.open("tokenizer/vocab.txt").bufferedReader().use { it.readLines() },
            app.assets.open("tokenizer/merges.txt").bufferedReader().use { it.readLines() },
        )
    }

    val encoder: ClipEncoder by lazy {
        modelCheck?.let { error(it) }   // 깨진 모델로 세션을 열지 않는다
        ClipEncoder(
            visionPath = File(modelDir, "vision_model_ko_int8.onnx"),
            textPath = File(modelDir, "text_model_ko_int8.onnx"),
            tokenizer = tokenizer,
            prep = ImagePrep.from(preprocessorJson),
            threads = bigCores(),
        )
    }

    /** 카테고리·세부종류 프롬프트에 씌우는 틀. 속성에는 씌우지 않는다(임계값이 현재 분포 기준). */
    private val templates: List<String> by lazy {
        config.optJSONArray("prompt_templates$variantSuffix")
            ?.let { a -> List(a.length()) { a.getString(it) } } ?: listOf("{}")
    }

    /**
     * 프롬프트 행렬. 텍스트 타워로 한 번 계산하고 DB에 캐시한다 — 문장 × 틀이라
     * 카테고리 14개면 ~190문장 × 12ms ≈ 2초. 키는 (모델, 프롬프트, 틀)의 해시다.
     * 셋 중 하나가 바뀌면 다시 만든다(§7.4).
     */
    private fun matrixFor(cacheKey: String, prompts: JSONObject, tpl: List<String>): PromptMatrix {
        val labels = prompts.keys().asSequence().toList()
        val stamp = "$modelId:${prompts.toString().hashCode()}:${tpl.hashCode()}"
        if (store.metaGet("${cacheKey}_stamp") == stamp) {
            store.metaGet(cacheKey)?.let { hex ->
                val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val rows = Embeddings.decode(bytes)
                if (rows.size == labels.size) return PromptMatrix(labels, rows)
            }
        }
        val rows = labels.map { label ->
            val arr = prompts.getJSONArray(label)
            // 문장 × 틀 전부 임베딩해 평균 → 정규화 (데스크톱 prompt_matrix와 동일)
            val sum = FloatArray(Embeddings.DIM)
            var n = 0
            for (i in 0 until arr.length()) for (t in tpl) {
                val v = encoder.encodeText(t.replace("{}", arr.getString(i)))
                for (d in sum.indices) sum[d] += v[d]
                n++
            }
            Embeddings.unit(FloatArray(Embeddings.DIM) { sum[it] / n })
        }
        val blob = ByteArray(rows.size * Embeddings.DIM * 4)
        rows.forEachIndexed { i, r -> Embeddings.encode(r).copyInto(blob, i * Embeddings.DIM * 4) }
        store.metaPut(cacheKey, blob.joinToString("") { "%02x".format(it) })
        store.metaPut("${cacheKey}_stamp", stamp)
        return PromptMatrix(labels, rows.toTypedArray())
    }

    fun promptMatrix(kind: String): PromptMatrix = matrixFor(
        "prompts_$kind", config.getJSONObject(kind + variantSuffix),
        if (kind == "attributes") listOf("{}") else templates,
    )

    /** 부모 라벨 → 세부 종류 행렬. 계층 2단계(§8.1). */
    fun subMatrices(): Map<String, PromptMatrix> {
        val subs = config.optJSONObject("subcategories$variantSuffix") ?: return emptyMap()
        return subs.keys().asSequence().associateWith { parent ->
            matrixFor("prompts_sub_${parent.hashCode()}", subs.getJSONObject(parent), templates)
        }
    }

    /**
     * 분류기. 카테고리 프로토타입에 **사용자 확정 사진을 섞는다**(§8.5) — 학습이 아니라
     * 기준점 이동이라 즉시 반영되고 피드백을 지우면 되돌아간다.
     */
    fun classifier(): Classifier {
        val alpha = config.optDouble("personalize_alpha", 0.5).toFloat()
        val minN = config.optInt("personalize_min_confirmed", 5)
        val cats = Personalize.apply(promptMatrix("categories"), store::confirmedEmbeddings, alpha, minN)
        return Classifier(cats, promptMatrix("attributes"),
            Classifier.Thresholds.from(config, variantSuffix), subMatrices())
    }

    /** 검색 인덱스. 앱이 사는 동안 메모리에 있고, 새 임베딩은 워커가 넣는다(§10.1). */
    val searchIndex: SearchIndex by lazy {
        SearchIndex.of(store.allEmbeddings())
    }

    fun queryParser(): QueryParser = QueryParser(
        knownTags = store.tagCounts().map { it.label }.toSet(),
        knownPlaces = store.events(500).mapNotNull { it.placeName }.toSet(),
    )

    /**
     * intra-op 스레드는 빅코어 수에 맞춘다. 리틀코어를 섞으면 느린 코어가 배리어를
     * 잡아 전체가 느려진다(§5.3). 정확히 알 방법이 없어 절반으로 어림한다.
     */
    private fun bigCores(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** ORT가 경로를 요구하는 파일만. 나머지는 assets에서 바로 읽는다. */
    val ONNX_ASSETS = listOf("vision_model_ko_int8.onnx", "text_model_ko_int8.onnx")

    private const val KEY_MODEL_STAMP = "model_asset_stamp"
}
