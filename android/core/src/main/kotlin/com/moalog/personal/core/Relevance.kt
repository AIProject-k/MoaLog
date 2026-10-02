package com.moalog.personal.core

/**
 * 검색 결과를 "가까운 결과"와 "그 밖의 결과"로 가르는 규칙.
 *
 * 검색은 늘 상위 N개를 채워 돌려준다. 뒤쪽은 문장과 거의 상관없는 사진인데도 앞쪽과 같은 모양으로 줄지어 있으면
 * 사용자가 "무엇을 검색해도 갤러리 전체가 나온다"고 느낀다(현재 디자인의 문제 8). 그래서 1등과의 유사도 차이가
 * [WINDOW] 안인 것만 "가까운 결과"로 보이고, 나머지는 접어 둔다.
 *
 * **점수는 확률이 아니라 유사도다.** 이 숫자는 화면에 내보이지 않는다 — 가르는 데만 쓴다.
 * 점수는 질의마다 오르내리므로 절대값이 아니라 1등과의 **차이**로 본다.
 */
object Relevance {
    /**
     * 1등과의 코사인 유사도 차이가 이 안이면 가깝다. 에뮬레이터 데모 갤러리(107장)에서 "dog"는 1등 0.286, 강아지 사진 14장이
     * 0.229까지 이어진 뒤 0.22 아래로 내려간다 — 그 경계 안쪽을 담으려고 0.05로 잡았다.
     */
    const val WINDOW = 0.05f

    /** 가까운 결과의 상한. 아무리 비슷해도 끝없이 이어지는 목록은 "가까운"이 아니다. */
    const val MAX_CLOSE = 60

    /** @param scores 유사도 **내림차순**. @return 앞에서부터 몇 개가 가까운 결과인가(결과가 있으면 최소 1). */
    fun closeCount(scores: List<Float>, window: Float = WINDOW, max: Int = MAX_CLOSE): Int {
        if (scores.isEmpty()) return 0
        val top = scores.first()
        return scores.takeWhile { it >= top - window }.size.coerceIn(1, max)
    }
}
