package com.moalog.personal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moalog.personal.R

/**
 * 디자인 토큰. `MoaLog Round 1 디자인.zip`의 `MoaLog App.dc.html`(방향 1a "조용한 갤러리")에서 뽑았다.
 *
 * **다크 단일 테마다.** 디자인이 "라이트는 Round 2에서 같은 역할 이름으로 값만 바꾼 쌍"이라고 남겼다.
 * 그때 이 클래스를 두 벌로 나눈다 — 화면 코드는 역할 이름(`surface1`, `text2`)만 쓰므로 바뀌지 않는다.
 *
 * 표면은 **톤 차이로만** 나눈다(그림자 없음). 강조색(하늘색)은 선택·주 동작에만, 붉은색은 휴지통에만 쓴다.
 */
@Immutable
data class Palette(
    /** 화면 배경 */
    val bg: Color = Color(0xFF0E0E10),
    /** 카드·시트·하단 바 */
    val surface1: Color = Color(0xFF16161A),
    /** 입력·세그먼트 트랙·검색창 */
    val surface2: Color = Color(0xFF1D1D22),
    /** 칩·눌림·활성 탭·보조 버튼 */
    val surface3: Color = Color(0xFF26262C),
    /** 본문 ≈16.9:1 */
    val text1: Color = Color(0xFFF2F0ED),
    /** 보조 ≈10.3:1 */
    val text2: Color = Color(0xFFBDBAB4),
    /** 메타 ≈5.9:1 (AA) */
    val text3: Color = Color(0xFF8E8B86),
    /** 선택·주 동작 ≈9.6:1 */
    val accent: Color = Color(0xFF8FB8FF),
    /** 강조 위 글자 ≈8.7:1 */
    val onAccent: Color = Color(0xFF0B1A33),
    /** 휴지통 채움 · 흰 글씨 ≈5.3:1 */
    val danger: Color = Color(0xFFC8322C),
    /** 위험 문구 ≈7.6:1 */
    val dangerText: Color = Color(0xFFFF7B72),
    /** 보관 추천·완료 ≈10.6:1 */
    val ok: Color = Color(0xFF6FD49A),
    /** 일시정지·경고 아이콘 */
    val warn: Color = Color(0xFFF2C66D),

    // ---- 템플릿 곳곳에서 직접 쓰는 보조 값 (역할이 하나뿐이라 이름만 붙였다)
    /** 탐색 막대 바탕 · 세그먼트 활성 · 스위치 꺼짐 트랙 */
    val track: Color = Color(0xFF3A3A42),
    /** 점선 · 대기 점 · 재생 실패 아이콘 · 자리표시 선 */
    val hint: Color = Color(0xFF5E5C59),
    /** 하단 탭 바 배경 */
    val tabBar: Color = Color(0xFF131316),
    /** 토스트 배경 */
    val toast: Color = Color(0xFF2E2E35),
    /** 검색 결과 확인 모드 머리띠 */
    val reviewBar: Color = Color(0xFF16233D),
    /** 확인 모드 머리띠 위 안내 글자 */
    val reviewText: Color = Color(0xFFDCE7FF),
    /** 구분선 rgba(255,255,255,.07) */
    val hair: Color = Color(0x12FFFFFF),
    /** 카드 안쪽 1dp 테두리 rgba(255,255,255,.08) */
    val ring: Color = Color(0x14FFFFFF),
    /** 윤곽 버튼 테두리 rgba(255,255,255,.16) */
    val ringStrong: Color = Color(0x29FFFFFF),
    /** 시트 뒤 어둡게 */
    val scrim: Color = Color(0x80000000),
    /** 뷰어 배경 */
    val viewer: Color = Color(0xFF000000),
)

/** 디자인의 글자 크기. `font-size/line-height/weight`를 그대로 옮겼고, 숫자는 전부 고정폭(tnum)이다. */
@Immutable
class Type(private val family: FontFamily) {
    private fun ts(size: Float, weight: FontWeight, line: Float? = null, spacing: Float = 0f) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (line ?: (size * 1.4f)).sp,
        letterSpacing = spacing.sp,
        fontFeatureSettings = "tnum",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    private val r = FontWeight.Normal
    private val m = FontWeight.Medium
    private val sb = FontWeight.SemiBold
    private val b = FontWeight.Bold

    /** 온보딩 제목 22/30/700 */
    val display = ts(22f, b, 30f)
    /** 분석 진행률 42/48/700 */
    val progress = ts(42f, b, 48f)
    /** 화면 제목 20/26/700 */
    val title = ts(20f, b, 26f)
    /** 상세 머리글 18/24/700 */
    val header = ts(18f, b, 24f)
    /** 시트·대화상자 제목 17/24/700 */
    val sheetTitle = ts(17f, b, 24f)
    /** 섹션 제목·목록 제목 15/22/600 */
    val item = ts(15f, sb, 22f)
    /** 설정 행 제목 14.5/20/600 */
    val itemMd = ts(14.5f, sb, 20f)
    /** 14/20/600 — 날짜 머리글·앨범 이름 */
    val label = ts(14f, sb, 20f)
    /** 본문 14/22/400 */
    val body = ts(14f, r, 22f)
    /** 14/21 — 검색 요약 */
    val body21 = ts(14f, r, 21f)
    /** 카드 안 본문 13.5/21 */
    val bodySm = ts(13.5f, r, 21f)
    /** 13.5/20/600 — 카드 안 강조·버튼 */
    val bodySmStrong = ts(13.5f, sb, 20f)
    /** 보조 설명 13/20/400 */
    val caption = ts(13f, r, 20f)
    /** 칩·세그먼트 13/600 */
    val chip = ts(13f, sb, 18f)
    /** 12.5/17/600 — 모아보기 카드 제목·작은 칩 */
    val chipSm = ts(12.5f, sb, 17f)
    /** 칩 본문(보통 굵기) 13/18/400 */
    val chipRegular = ts(13f, r, 18f)
    /** 12.5/19 — 안내 문장 */
    val note = ts(12.5f, r, 19f)
    /** 메타 12/16/400 */
    val meta = ts(12f, r, 16f)
    /** 메타 강조 12/16/600 */
    val metaStrong = ts(12f, sb, 16f)
    /** 섹션 라벨 12/600 +0.08em */
    val sectionLabel = ts(12f, sb, 16f, 0.96f)
    /** 11.5/17 — 아주 작은 안내 */
    val micro = ts(11.5f, r, 17f)
    /** 배지·탭 라벨 11/14/600 */
    val badge = ts(11f, sb, 14f)
    /** 주 버튼 15/600 */
    val button = ts(15f, sb, 20f)
    /** 입력창 15/400 */
    val input = ts(15f, r, 22f)
    /** 숫자 라벨 9/700 — 되감기·빨리 감기 아이콘 안 */
    val tiny = ts(9f, b, 12f)
    /** 11/500 — 하단 선택 바 라벨 */
    val barLabel = ts(11f, m, 14f)
}

/** 디자인이 쓰는 반지름. 값은 역할 이름으로만 쓴다. */
object Radius {
    /** 세그먼트 안쪽 */
    val segInner = 9.dp
    /** 정리 비교 타일·목록 작은 썸네일 */
    val tile = 10.dp
    /** 앨범 썸네일·모아보기 카드·세그먼트 트랙·입력창·토스트 */
    val thumb = 12.dp
    /** 메뉴 */
    val menu = 14.dp
    /** 카드 */
    val card = 16.dp
    /** 시트·대화상자 */
    val sheet = 20.dp
}

val LocalPalette: ProvidableCompositionLocal<Palette> = staticCompositionLocalOf { Palette() }
val LocalType: ProvidableCompositionLocal<Type> = staticCompositionLocalOf { Type(FontFamily.SansSerif) }

/**
 * IBM Plex Sans KR (OFL 1.1, 재배포 가능)을 앱에 넣는다. 다운로더블 폰트를 쓰지 않는 이유는
 * 그게 Play 서비스와 네트워크를 타기 때문이다 — 인터넷은 지도 탭의 지도 조각 받기에만 쓴다.
 * 라이선스 문구는 `assets/licenses/IBMPlexSansKR-OFL.txt`.
 */
private fun plexKr(): FontFamily = runCatching {
    FontFamily(
        Font(R.font.ibm_plex_sans_kr_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_kr_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_kr_bold, FontWeight.Bold),
    )
}.getOrDefault(FontFamily.SansSerif)

@Composable
fun PhotoLogTheme(content: @Composable () -> Unit) {
    val palette = Palette()
    val type = Type(plexKr())
    CompositionLocalProvider(LocalPalette provides palette, LocalType provides type) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                background = palette.bg,
                surface = palette.surface1,
                onBackground = palette.text1,
                onSurface = palette.text1,
            ),
            content = content,
        )
    }
}

/** 화면에서 짧게 쓰려고 둔다: `theme.accent`, `fonts.body` */
val theme: Palette
    @Composable get() = LocalPalette.current
val fonts: Type
    @Composable get() = LocalType.current

/** 한 줄로 줄이는 흔한 조합. */
val Ellipsis = TextOverflow.Ellipsis
