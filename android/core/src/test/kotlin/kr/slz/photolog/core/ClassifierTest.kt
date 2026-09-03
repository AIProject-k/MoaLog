package kr.slz.photolog.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 분류 로직 대조. 데스크톱이 사진 64장에 붙인 1순위 라벨과 같아야 한다.
 *
 * ONNX도 기기도 필요 없다 — 계약에 든 임베딩과 프롬프트 행렬을 그대로 먹인다.
 * 여기서 통과하면 폰에서 남은 변수는 "임베딩이 같게 나오는가" 하나뿐이고,
 * 그건 Phase 0에서 실기기로 잰다.
 */
class ClassifierTest {

    private val fixtures = File("../../eval/fixtures").canonicalFile
    private val config = JSONObject(File("../../config.json").canonicalFile.readText())
    private val manifest by lazy { JSONObject(File(fixtures, "manifest.json").readText()) }

    private fun promptMatrix(kind: String): PromptMatrix {
        val meta = manifest.getJSONObject("prompts").getJSONObject(kind)
        val labels = meta.getJSONArray("labels").let { a -> List(a.length()) { a.getString(it) } }
        return PromptMatrix(labels, Embeddings.read(File(fixtures, "prompts_$kind.bin")))
    }

    /** 세부 종류 행렬. 한 파일에 이어 붙어 있고 manifest가 부모별 offset을 안다. */
    private fun subMatrices(): Map<String, PromptMatrix> {
        val idx = manifest.getJSONObject("prompts").optJSONObject("sub") ?: return emptyMap()
        val all = Embeddings.read(File(fixtures, "prompts_sub.bin"))
        return idx.keys().asSequence().associateWith { parent ->
            val m = idx.getJSONObject(parent)
            val labels = m.getJSONArray("labels").let { a -> List(a.length()) { a.getString(it) } }
            val off = m.getInt("offset")
            PromptMatrix(labels, Array(m.getInt("count")) { all[off + it] })
        }
    }

    private val classifier by lazy {
        // suffix는 데스크톱과 같은 규칙: ko 모델이면 "_ko" 세트를 쓴다.
        val suffix = if (manifest.getString("model_variant") == "ko") "_ko" else ""
        Classifier(promptMatrix("categories"), promptMatrix("attributes"),
                   Classifier.Thresholds.from(config, suffix), subMatrices())
    }

    private val subLabels by lazy {
        manifest.getJSONObject("prompts").optJSONObject("sub")?.let { idx ->
            idx.keys().asSequence().flatMap { pr ->
                idx.getJSONObject(pr).getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }.asSequence()
            }.toSet()
        } ?: emptySet()
    }

    private fun expected(): List<Pair<String, String>> {
        val arr = JSONArray(File(fixtures, "images_ko.json").readText())
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            o.getString("file") to o.getString("top1")
        }
    }

    @Test
    fun `임베딩 64장을 읽는다`() {
        val v = Embeddings.read(File(fixtures, "images_ko.bin"))
        assertEquals(64, v.size)
        assertEquals(512, v[0].size)
        // 저장된 벡터는 L2 정규화된 상태여야 한다 — 내적을 코사인으로 쓰는 전제다.
        v.forEach { assertTrue(kotlin.math.abs(Embeddings.dot(it, it) - 1f) < 1e-3f) }
    }

    @Test
    fun `1순위 라벨이 데스크톱과 전부 같다`() {
        val vecs = Embeddings.read(File(fixtures, "images_ko.bin"))
        val want = expected()
        assertEquals(want.size, vecs.size)
        val bad = want.indices.filter { classifier.top1(vecs[it]) != want[it].second }
        if (bad.isNotEmpty()) {
            val report = bad.take(6).joinToString("\n") {
                "  ${want[it].first.take(44)}\n    기대 ${want[it].second}  실제 ${classifier.top1(vecs[it])}"
            }
            error("${bad.size}/${want.size} 불일치:\n$report")
        }
    }

    @Test
    fun `세부 종류가 데스크톱과 전부 같다`() {
        val vecs = Embeddings.read(File(fixtures, "images_ko.bin"))
        val arr = JSONArray(File(fixtures, "images_ko.json").readText())
        var same = 0; var withSub = 0
        val bad = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val want = if (o.isNull("sub")) null else o.getString("sub")
            val mine = classifier.classify(vecs[i]).firstOrNull { it.label in subLabels }?.label
            if (want != null) withSub++
            if (want == mine) same++ else if (bad.length < 400)
                bad.append("\n  ${o.getString("file").take(36)}: 기대 $want 실제 $mine")
        }
        assertTrue(withSub > 40, "계약에 세부 종류가 거의 없다 ($withSub/64) — 픽스처가 낡았나")
        assertEquals(arr.length(), same, "세부 종류 불일치:$bad")
    }

    @Test
    fun `세부 종류는 부모 안에서만 나온다 — 다른 부모의 것이 섞이지 않는다`() {
        val vecs = Embeddings.read(File(fixtures, "images_ko.bin"))
        val subs = config.getJSONObject("subcategories_ko")
        for (v in vecs) {
            val tags = classifier.classify(v)
            val parent = tags[0].label
            val sub = tags.firstOrNull { it.label in subLabels } ?: continue
            val siblings = subs.optJSONObject(parent)?.keys()?.asSequence()?.toSet() ?: emptySet()
            assertTrue(sub.label in siblings, "'$parent' 사진에 다른 부모의 세부 종류 '${sub.label}'이 붙었다")
        }
    }

    @Test
    fun `임계값을 config에서 읽는다 — 코드에 박혀 있지 않다`() {
        val t = Classifier.Thresholds.from(config, "_ko")
        assertEquals(config.getDouble("classify_min_score").toFloat(), t.classifyMin)
        assertEquals(config.getDouble("attr_tag_threshold_ko").toFloat(), t.attrThreshold,
            "ko 전용 임계값을 쓰지 않고 공용값으로 떨어졌다")
        assertTrue("문서/영수증" in t.primaryOnly)
    }

    @Test
    fun `확신이 없으면 미분류를 붙인다`() {
        // 어떤 카테고리와도 비슷하지 않은 벡터 → softmax가 평평해져 1순위도 임계 미만
        val flat = Embeddings.unit(FloatArray(512) { 1f })
        val tags = classifier.classify(flat)
        assertTrue(tags.isNotEmpty(), "태그가 하나도 없으면 안 된다 — 미분류라도 붙여야 한다")
    }

    @Test
    fun `문서와 화면캡처는 2순위로 붙지 않는다`() {
        val vecs = Embeddings.read(File(fixtures, "images_ko.bin"))
        val t = Classifier.Thresholds.from(config, "_ko")
        for (v in vecs) {
            val tags = classifier.classify(v)
            tags.drop(1).forEach { tag ->
                assertTrue(tag.label !in t.primaryOnly || tags[0].label == tag.label,
                    "'${tag.label}'이 2순위로 붙었다 — primary_only가 안 걸렸다")
            }
        }
    }
}
