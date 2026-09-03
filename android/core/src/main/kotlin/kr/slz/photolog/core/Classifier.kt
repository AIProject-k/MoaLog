package kr.slz.photolog.core

import org.json.JSONObject
import kotlin.math.exp

/**
 * 사진 한 장에 붙는 태그. source는 어디서 나왔는지 — UI가 배지로 보여 준다.
 * 규칙 태그는 점수가 없다(null). 확률도 코사인도 아닌 판정이라 숫자를 지어낼 이유가 없다.
 */
data class Tag(val label: String, val score: Float?, val source: String)

/** 프롬프트 앙상블 행렬. `rows[i]`가 `labels[i]`의 512차원 단위벡터. */
class PromptMatrix(val labels: List<String>, val rows: Array<FloatArray>) {
    init { require(labels.size == rows.size) { "라벨 ${labels.size}개 ≠ 행 ${rows.size}개" } }

    /** 각 라벨과의 코사인. 둘 다 L2 정규화돼 있으므로 내적이 곧 코사인이다. */
    fun scores(vec: FloatArray): FloatArray = FloatArray(rows.size) { i ->
        var s = 0f
        val r = rows[i]
        for (k in r.indices) s += r[k] * vec[k]
        s
    }
}

/**
 * 제로샷 분류. `encoder.py`의 `classify()`를 그대로 옮긴 것 — 이 로직의 숫자가
 * 데스크톱 정확도 0.86의 근거다. 임의로 손대지 않는다.
 *
 * 두 척도가 섞여 있다는 점이 핵심이다:
 *  - **카테고리**는 softmax 확률(0~1). CLIP 원 코사인은 0.15~0.35 좁은 구간에 몰려서
 *    사진 간 비교가 안 된다. 학습된 온도(logitScale ≈ 100)를 곱해야 확률이 된다.
 *  - **속성**은 원 코사인 그대로. 셀카이면서 실외일 수 있으니 서로 배타적이지 않고,
 *    배타적이지 않으면 softmax를 쓸 수 없다. 그래서 임계값으로 자른다.
 *    이 임계값은 모델마다 분포가 달라 variant별로 둔다(ANDROID.md §8.3).
 */
class Classifier(
    private val categories: PromptMatrix,
    private val attributes: PromptMatrix,
    private val cfg: Thresholds,
    /**
     * 부모 라벨 → 세부 종류 행렬. 계층 2단계다 — 부모 1순위가 정해진 뒤 **그 부모의
     * 형제끼리만** softmax를 돈다. 평면으로 늘리면 제로섬(8→14개에서 0.86→0.84)이지만
     * 계층은 다른 부모의 표를 빼앗지 않아 부모 정확도가 구조적으로 그대로다.
     */
    private val subcategories: Map<String, PromptMatrix> = emptyMap(),
) {
    data class Thresholds(
        val logitScale: Float,
        val classifyMin: Float,
        val secondaryMin: Float,
        val maxCategories: Int,
        /** 세부 종류가 붙는 최소 softmax 확률. 형제 3~7개 중 하나가 확실할 때만 */
        val subMin: Float,
        /** 속성별 임계값이 없을 때의 공용값 */
        val attrThreshold: Float,
        /**
         * 속성별 원 코사인 임계값. 전역값 하나로는 안 된다 — 분포가 속성마다 달라
         * 같은 0.225가 어떤 속성에는 p99, 어떤 속성에는 p70이다. 실측으로 '지도화면'이
         * 풍경 사진 25장에, '눈비'가 반려동물 15장에 붙었다(ANDROID.md §8.3).
         */
        val attrPerLabel: Map<String, Float>,
        val primaryOnly: Set<String>,
    ) {
        fun attrFor(label: String): Float = attrPerLabel[label] ?: attrThreshold

        companion object {
            /** 데스크톱과 **같은 config.json**을 읽는다. suffix는 ""(en) 또는 "_ko". */
            fun from(config: JSONObject, suffix: String) = Thresholds(
                logitScale = config.optDouble("logit_scale", 100.0).toFloat(),
                classifyMin = config.getDouble("classify_min_score").toFloat(),
                secondaryMin = config.optDouble("secondary_min_score", 1.01).toFloat(),
                maxCategories = config.optInt("max_categories", 1),
                subMin = config.optDouble("sub_min_score", 0.4).toFloat(),
                // variant별 임계값이 있으면 그것, 없으면 공용값. 원 코사인이라 이게 중요하다.
                attrThreshold = config.optDouble(
                    "attr_tag_threshold$suffix",
                    config.getDouble("attr_tag_threshold"),
                ).toFloat(),
                attrPerLabel = config.optJSONObject("attr_thresholds$suffix")?.let { o ->
                    o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
                } ?: emptyMap(),
                primaryOnly = config.optJSONArray("primary_only")
                    ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    ?: emptySet(),
            )
        }
    }

    fun classify(vec: FloatArray): List<Tag> {
        val out = ArrayList<Tag>(4)
        val p = softmax(categories.scores(vec), cfg.logitScale)
        val order = p.indices.sortedByDescending { p[it] }

        // 1순위는 언제나 붙인다. 확신이 없으면 '미분류'로 — 태그가 없는 것과 다르다.
        val top = order[0]
        out.add(if (p[top] >= cfg.classifyMin) Tag(categories.labels[top], p[top], "clip")
                else Tag(UNCLASSIFIED, p[top], "clip"))

        // 대분류는 배타적이지 않다. '창가 캣타워의 고양이'는 반려동물이면서 실내일상이다.
        // 단 primaryOnly(문서·화면캡처)는 2순위로 붙이지 않는다 — '약간 문서인 사진'은
        // 말이 안 되고, 허용하면 그 앨범이 잡동사니가 된다(정밀도 0.72→0.61).
        for (j in 1 until minOf(cfg.maxCategories, order.size)) {
            val idx = order[j]
            val lab = categories.labels[idx]
            if (p[idx] >= cfg.secondaryMin && lab !in cfg.primaryOnly) out.add(Tag(lab, p[idx], "clip"))
        }

        // 2단계: 부모가 확실할 때만 세부 종류를 본다. 못 넘으면 부모 태그만 남는다 —
        // 그건 실패가 아니라 정직한 결과다.
        if (p[top] >= cfg.classifyMin) {
            subcategories[categories.labels[top]]?.let { sub ->
                val sp = softmax(sub.scores(vec), cfg.logitScale)
                val j = sp.indices.maxByOrNull { sp[it] } ?: return@let
                if (sp[j] >= cfg.subMin) out.add(Tag(sub.labels[j], sp[j], "clip"))
            }
        }

        val a = attributes.scores(vec)
        for (i in attributes.labels.indices) {
            val label = attributes.labels[i]
            if (a[i] >= cfg.attrFor(label)) out.add(Tag(label, a[i], "clip"))
        }
        return out
    }

    /** 1순위 라벨만. 평가·대조에서 쓴다. */
    fun top1(vec: FloatArray): String = classify(vec).first().label

    private fun softmax(cos: FloatArray, scale: Float): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        val logits = FloatArray(cos.size) { (cos[it] * scale).also { v -> if (v > max) max = v } }
        var sum = 0f
        val p = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat().also { v -> sum += v } }
        for (i in p.indices) p[i] /= sum
        return p
    }

    companion object {
        const val UNCLASSIFIED = "미분류"
    }
}
