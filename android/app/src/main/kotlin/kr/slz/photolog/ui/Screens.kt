package kr.slz.photolog.ui

/**
 * 디자인의 `screen` 상태를 그대로 옮긴 것. `design/AI Gallery Auto-Sort.dc.html`의
 * JUMPS·NAV 목록과 1:1로 맞춘다 — 이름이 어긋나면 디자인과 코드를 대조할 수 없다.
 */
enum class Screen(val tabLabel: String = "") {
    Permission,
    Analyzing,
    Home("홈"),
    Albums("앨범"),
    Category,
    Photo,
    Search("검색"),
    Fix,
    Cleanup,
    Settings("설정");

    val showsNav get() = this in tabs

    companion object {
        /** 하단 탭에 나오는 넷. 디자인의 NAV 순서를 지킨다. */
        val tabs = listOf(Home, Albums, Search, Settings)
    }
}

/** 사진 권한 상태. 권한이 없으면 다른 화면보다 안내 화면을 우선한다. */
enum class Access { None, Partial, Full }

fun startupScreen(access: Access, current: Screen): Screen =
    if (access == Access.None) Screen.Permission else current

/** 디자인의 `axes` — 앨범 화면 상단 필터. "전체"는 필터 없음을 뜻한다. */
enum class Axis(val label: String) {
    All("전체"), Person("인물"), Travel("여행"), Doc("문서"), Food("음식"), Moment("순간");

    companion object { val ordered = entries }
}

/**
 * 분석 진행 단계. 디자인은 4단계를 %로 보여 준다.
 *
 * **디자인의 라벨과 실제 파이프라인을 맞춰 뒀다.** 디자인 원문은 "얼굴 묶기"·"장소·이동
 * 경로 정리"인데, 얼굴 신원 묶기는 3단계 기능이고(ANDROID.md §8.4) 지금은 없다.
 * 없는 일을 하고 있다고 표시하면 사용자를 속이는 것이므로, 지금 실제로 도는 단계로 적는다.
 * 기능이 들어오면 라벨을 디자인 원문으로 되돌린다.
 */
enum class AnalyzeStep(val label: String, val startAt: Int) {
    Scan("사진 목록 읽기", 0),
    Meta("촬영 정보·출처 정리", 12),
    Clip("장면 분류", 30),
    Group("앨범 묶기", 88);

    companion object { val ordered = entries }
}

/** 대기 → 진행 → 완료. 디자인의 `s.state`. */
enum class StepState(val label: String) { Waiting("대기"), Running("진행"), Done("완료") }

fun AnalyzeStep.stateAt(progress: Int): StepState {
    val next = AnalyzeStep.ordered.getOrNull(ordinal + 1)?.startAt ?: 101
    return when {
        progress >= next -> StepState.Done
        progress >= startAt -> StepState.Running
        else -> StepState.Waiting
    }
}
