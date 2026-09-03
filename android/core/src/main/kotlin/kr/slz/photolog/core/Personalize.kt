package kr.slz.photolog.core

/**
 * 학습 없는 개인화 (ANDROID.md §8.5).
 *
 * 사용자가 "맞아요"라고 확정한 사진의 임베딩을 그 카테고리의 프로토타입에 섞는다:
 *
 *     proto = unit( text_proto + α · mean(confirmed_image_embeds) )
 *
 * 경사도 학습이 아니라 **기준점 이동**이다. 즉시 반영되고, 피드백을 지우면 되돌아간다.
 * 이 사용자의 "음식"이 무엇인지를 그 사람의 사진으로 조금 당겨 놓는 것이다.
 *
 * 부정 피드백("이건 음식 아님")은 쓰지 않는다 — 빼기는 수학이 지저분하고, 긍정만으로
 * 충분한지 먼저 보는 것이 순서다(`ponytail`). 확정이 [minConfirmed]장 미만이면 섞지 않는다:
 * 한두 장으로 프로토타입을 끌면 그 사진들만 맞고 나머지가 틀린다.
 */
object Personalize {

    fun blend(textProto: FloatArray, confirmed: List<FloatArray>, alpha: Float, minConfirmed: Int): FloatArray {
        if (confirmed.size < minConfirmed || alpha <= 0f) return textProto
        val dim = textProto.size
        val mean = FloatArray(dim)
        for (v in confirmed) for (d in 0 until dim) mean[d] += v[d]
        val inv = 1f / confirmed.size
        return Embeddings.unit(FloatArray(dim) { textProto[it] + alpha * mean[it] * inv })
    }

    /** 카테고리 행렬 전체에 적용. 확정 사진이 없는 라벨은 그대로 둔다. */
    fun apply(
        matrix: PromptMatrix,
        confirmedByLabel: (String) -> List<FloatArray>,
        alpha: Float,
        minConfirmed: Int,
    ): PromptMatrix = PromptMatrix(
        matrix.labels,
        Array(matrix.rows.size) { i -> blend(matrix.rows[i], confirmedByLabel(matrix.labels[i]), alpha, minConfirmed) },
    )
}
