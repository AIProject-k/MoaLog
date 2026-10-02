package com.moalog.personal.ui

/**
 * 화면 상태 기계. 내비게이션 라이브러리 없이 이 enum 하나로 돈다.
 *
 * **하단 탭이 사진 / 앨범 / 검색 / 정리로 바뀌었다.** 이전에는 홈·앨범·검색·설정이었는데
 * 두 가지가 문제였다:
 *  - 설정은 하루에 한 번도 안 누르는 화면인데 탭 하나를 차지했다. 정리는 매번 쓰는데
 *    설정 안에 숨어 있었다. 자리를 맞바꾼다.
 *  - 홈이 앨범 목록을 다시 그려서 앨범 탭과 겹쳤다. 홈을 [Photos]로 바꾸고 **전체 사진**을
 *    맡긴다 — 분류를 몰라도 사진에 닿을 수 있어야 갤러리다.
 */
enum class Screen(val tabLabel: String = "") {
    Permission,
    Analyzing,

    /** 전체 사진. 위에 추억·이번 주 모음이 작게 붙는다. */
    Photos("갤러리"),
    Albums("앨범"),

    /** 앨범 하나를 연 상태(태그·이벤트·기기 폴더·내 앨범 공통). */
    Category,

    /** 전체 화면 사진 보기. 좌우로 넘기고 확대할 수 있다. */
    Viewer,
    Search("검색"),
    Fix,
    Cleanup("정리"),
    Settings;

    val showsNav get() = this in tabs

    companion object {
        val tabs = listOf(Photos, Albums, Search, Cleanup)
    }
}

/** 사진 권한 상태. 권한이 없으면 다른 화면보다 안내 화면을 우선한다. */
enum class Access { None, Partial, Full }

fun startupScreen(access: Access, current: Screen): Screen =
    if (access == Access.None) Screen.Permission else current

/**
 * 앨범의 **출처**. 사용자가 가장 먼저 알아야 하는 구분이다 —
 * "카메라" 폴더에서 사진을 빼는 것과 "음식" 분류에서 빼는 것은 완전히 다른 행동이다.
 */
enum class AlbumKind(val label: String, val chip: String) {
    /** 사용자가 직접 만든 모음. */
    User("내 앨범", "내 앨범"),

    /** 카메라·스크린샷·다운로드 등 실제 저장 위치. */
    Folder("기기 폴더", "폴더"),

    /** CLIP·규칙이 내용으로 묶은 분류. */
    Tag("AI 앨범", "AI 분류"),

    /** 시간·장소로 묶인 기록. */
    Event("기록", "기록"),

    /** 사진 탭 위의 모아보기(이번 주·N년 전 오늘). 앨범 목록에는 나오지 않고 앨범 상세 화면만 연다. */
    Memory("모아보기", "모아보기");

    companion object { val ordered = entries.filter { it != Memory } }
}

/** 앨범 화면 상단 필터. "전체"는 필터 없음을 뜻한다. */
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
 *
 * **시작 지점도 실제 진행률 곡선에 맞췄다.** 디자인(12·30·88%)은 타이머가 올리는 값이고, 실제로는
 * 촬영 정보 정리(Pass 1)가 0~15%, 장면 분류(Pass 2)가 15~100%다([com.moalog.personal.data.Counts.progress]).
 * 디자인 값대로 두면 장면 분류가 한창인데 "촬영 정보 정리 중"이라고 적힌다.
 */
enum class AnalyzeStep(val label: String, val startAt: Int) {
    Scan("사진 목록 읽기", 0),
    Meta("촬영 정보·출처 정리", 3),
    Clip("장면 분류", 15),
    Group("앨범 묶기", 97);

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

/** 화면 위에 뜨는 하단 시트. 한 번에 하나만 뜬다. */
enum class Sheet { Add, Month, Axis, Move, Licenses }

/** 되돌릴 수 없는 일을 한 번 더 묻는 대화상자. */
enum class Dialog { DeleteAlbum, DeleteAll }

/**
 * 화면 아래 토스트 한 줄. `action`이 있으면 오른쪽에 동작 버튼("되돌리기"·"앨범 보기")이 붙고,
 * 누르면 `run`이 돈다.
 */
data class ToastMsg(val text: String, val action: String? = null, val run: (() -> Unit)? = null)
