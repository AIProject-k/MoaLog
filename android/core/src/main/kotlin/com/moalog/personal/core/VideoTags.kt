package com.moalog.personal.core

import kotlin.math.ceil

/**
 * 영상의 분류 태그. 프레임마다 분류한 결과를 **투표**로 합친다.
 *
 * 예전에는 프레임 중 하나라도 나온 라벨을 전부 붙였다(합집합). 프레임이 5~96장이라 어느 라벨이든
 * 한 번쯤은 걸려서 영상당 AI 태그가 평균 9.1개(2~22개) 붙었다 — 같은 갤러리의 사진은 4.2개다. 결과는
 * "지도화면"이 영상 42개에, "셀카"가 영상의 48%에 붙는 식이었고, 영상만 따로 분류해 보여 주는
 * 앨범이 쓸모없어졌다. 라벨이 영상의 **상당한 부분**에서 보일 때만 그 영상의 태그로 본다.
 *
 * 순수 함수다. 프레임 벡터는 DB에 남아 있으므로 규칙을 바꿔도 프레임을 다시 뽑지 않고 저장된
 * 벡터로 태그만 다시 만들 수 있다([RULE]).
 */
object VideoTags {
    /** 프레임의 이 비율 이상에서 나온 라벨만 영상의 태그가 된다. */
    const val MIN_SHARE = 0.4f

    /** 한 영상에 붙는 분류 태그의 상한. 득표가 많은 순으로 남긴다. */
    const val MAX_TAGS = 5

    /**
     * 규칙의 도장. 값이 바뀌면 워커가 저장된 프레임으로 태그를 한 번 다시 만든다 —
     * 프레임 추출 방식([VideoSampling.STAMP])과 따로 두는 이유는 태그 규칙을 고칠 때마다
     * 영상을 다시 열어 프레임을 뽑을 필요가 없어서다.
     */
    const val RULE = "vote$MIN_SHARE-max$MAX_TAGS"

    /**
     * @param frames 프레임마다의 분류 결과. 각 목록의 **첫 항목이 그 프레임의 1순위 대분류**다
     *  ([Classifier.classify]의 규약).
     */
    fun aggregate(
        frames: List<List<Tag>>,
        minShare: Float = MIN_SHARE,
        maxTags: Int = MAX_TAGS,
    ): List<Tag> {
        val n = frames.size
        if (n == 0) return emptyList()

        val votes = HashMap<String, Int>()
        val best = HashMap<String, Float>()
        val primary = HashMap<String, Int>()
        for (tags in frames) {
            for (t in tags.distinctBy { it.label }) {
                votes.merge(t.label, 1, Int::plus)
                best.merge(t.label, t.score ?: 0f, ::maxOf)
            }
            tags.firstOrNull()?.let { primary.merge(it.label, 1, Int::plus) }
        }

        val need = maxOf(1, ceil(minShare * n).toInt())
        val kept = votes.filter { (label, v) -> v >= need && label != Classifier.UNCLASSIFIED }.keys.toMutableList()

        // 사진의 규칙과 같다: 대분류 1순위는 언제나 붙인다. 통과한 대분류가 없으면 가장 자주 1순위였던
        // 라벨을 붙인다 — 태그 없이 남은 영상은 어느 앨범에도 나오지 않는다. 그 라벨도 '미분류'뿐이면 그것을 붙인다.
        if (kept.none { it in primary }) {
            val lead = primary.filterKeys { it != Classifier.UNCLASSIFIED }
                .maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }, { best[it.key] ?: 0f }))?.key
                ?: Classifier.UNCLASSIFIED
            if (lead !in kept) kept.add(lead)
        }

        return kept
            .sortedWith(compareByDescending<String> { votes[it] ?: 0 }.thenByDescending { best[it] ?: 0f })
            .take(maxTags)
            .map { Tag(it, best[it], "clip") }
    }
}
