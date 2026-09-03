package kr.slz.photolog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.slz.photolog.R

/**
 * 디자인 토큰. `design/AI Gallery Auto-Sort.dc.html`에서 그대로 뽑았다.
 *
 * **단일 다크 테마다.** 디자인이 한 가지 시각 세계에 확정적으로 커밋해 있고
 * (radial-gradient 배경, 어두운 서피스 위 주홍 악센트), 라이트 대응이 없다.
 * 그래서 [isSystemInDarkTheme]을 보지 않고 항상 이 팔레트를 쓴다 — 빠뜨린 게
 * 아니라 선택이다. 라이트가 필요해지면 그때 [Palette]를 두 벌로 나눈다.
 *
 * 값을 코드에 흩뿌리지 않는 이유는 config.json과 같다: 디자인이 바뀌면 한 파일만 고친다.
 */
@Immutable
data class Palette(
    /** 기기 화면 바탕. 디자인의 캔버스 바깥(#08080A)은 앱에 해당 없음 */
    val bg: Color = Color(0xFF0E0E11),
    /** 사진 상세처럼 더 가라앉는 화면 */
    val bgDeep: Color = Color(0xFF08080A),
    /** 카드 — 낮은 단계 */
    val surface: Color = Color(0xFF141418),
    /** 카드 — 기본 (검색바, 알림 카드, 설정 항목) */
    val surface2: Color = Color(0xFF17171B),
    /** 칩·아이콘 버튼 */
    val surface3: Color = Color(0xFF1A1A20),
    /** 눌린/진행 상태 */
    val surface4: Color = Color(0xFF1F1F25),
    /** 아바타·비활성 트랙 */
    val surface5: Color = Color(0xFF22222A),
    /** hover 대응 (Compose에서는 pressed) */
    val surfaceHi: Color = Color(0xFF26262E),
    val text: Color = Color(0xFFF2F0ED),
    val accent: Color = Color(0xFFE8503A),
    val accentHi: Color = Color(0xFFF4795F),
    val ok: Color = Color(0xFF5BD08A),
    /** 구분선 — 디자인의 rgba(242,240,237,.07) */
    val divider: Color = Color(0x12F2F0ED),
    /** 입력 테두리 — rgba(242,240,237,.1) */
    val outline: Color = Color(0x1AF2F0ED),
) {
    /** 본문 위 텍스트 투명도. 디자인이 20단계를 쓰는데 의미는 네 층이다. */
    val textStrong: Color get() = text.copy(alpha = .90f)   // 값
    val textBody: Color get() = text.copy(alpha = .72f)     // 설명문
    val textMuted: Color get() = text.copy(alpha = .50f)    // 보조
    val textFaint: Color get() = text.copy(alpha = .35f)    // 섹션 라벨·플레이스홀더

    /** 악센트 배경 틴트. 디자인이 .06~.30을 쓴다. */
    fun accentTint(alpha: Float) = accent.copy(alpha = alpha)
}

/** 디자인의 accentColor prop — 설정에서 바꿀 수 있는 네 가지. */
enum class AccentChoice(val color: Color, val label: String) {
    Vermilion(Color(0xFFE8503A), "주홍"),
    Lime(Color(0xFFB4F461), "라임"),
    Indigo(Color(0xFF3B2FD4), "인디고"),
    Pine(Color(0xFF1F6F5C), "청록"),
}

@Immutable
data class Type(
    val family: FontFamily,
    /** 27 / 900 / -.02em — 온보딩 제목 */
    val display: TextStyle = TextStyle(
        fontFamily = family, fontSize = 27.sp, fontWeight = FontWeight.Black,
        lineHeight = 35.6.sp, letterSpacing = (-0.54).sp,
    ),
    /** 42 / 900 — 진행률 숫자 */
    val progress: TextStyle = TextStyle(
        fontFamily = family, fontSize = 42.sp, fontWeight = FontWeight.Black,
        letterSpacing = (-1.26).sp,
    ),
    /** 24 / 900 — 화면 제목 */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = family, fontSize = 24.sp, fontWeight = FontWeight.Black,
        letterSpacing = (-0.48).sp,
    ),
    /** 19 / 700 — 히어로 카드 제목 */
    val hero: TextStyle = TextStyle(
        fontFamily = family, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.19).sp,
    ),
    /** 17 / 700 — 상세 헤더 */
    val header: TextStyle = TextStyle(
        fontFamily = family, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.17).sp,
    ),
    /** 15 / 700 — 목록 항목 제목 */
    val itemTitle: TextStyle = TextStyle(
        fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.15).sp,
    ),
    /** 14 / 400 — 본문 */
    val body: TextStyle = TextStyle(
        fontFamily = family, fontSize = 14.sp, lineHeight = 23.8.sp,
    ),
    /** 13.5 / 400 — 카드 안 본문 */
    val bodySm: TextStyle = TextStyle(
        fontFamily = family, fontSize = 13.5.sp, lineHeight = 22.3.sp,
    ),
    /** 13 / 400 — 보조 설명 */
    val caption: TextStyle = TextStyle(
        fontFamily = family, fontSize = 13.sp, lineHeight = 20.8.sp,
    ),
    /** 12 / 400 — 메타 */
    val meta: TextStyle = TextStyle(fontFamily = family, fontSize = 12.sp),
    /** 12 / 700 / .1em — 섹션 라벨 */
    val sectionLabel: TextStyle = TextStyle(
        fontFamily = family, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    ),
    /** 11 / 400 / .18em — 대문자 에이브로우 */
    val eyebrow: TextStyle = TextStyle(
        fontFamily = family, fontSize = 11.sp, letterSpacing = 1.98.sp,
    ),
    /** 12.5 / 400 — 칩 */
    val chip: TextStyle = TextStyle(fontFamily = family, fontSize = 12.5.sp),
    /** 10.5 / 700 — 하단 탭 라벨, 배지 */
    val badge: TextStyle = TextStyle(
        fontFamily = family, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.84.sp,
    ),
    /** 16 / 700 — 주 버튼 */
    val button: TextStyle = TextStyle(
        fontFamily = family, fontSize = 16.sp, fontWeight = FontWeight.Bold,
    ),
)

/** 디자인이 쓰는 반지름. 값이 많아 보이지만 역할은 다섯 가지다. */
object Radius {
    val pill = 27.dp        // 주 버튼 (h54의 절반)
    val field = 22.dp       // 입력·검색바 (h44의 절반)
    val card = 16.dp        // 카드 기본
    val cardLg = 20.dp      // 히어로 카드
    val chip = 14.dp        // 칩
    val thumb = 12.dp       // 목록 썸네일
    val tile = 4.dp         // 온보딩 그리드 조각
}

val LocalPalette: ProvidableCompositionLocal<Palette> = staticCompositionLocalOf { Palette() }
val LocalType: ProvidableCompositionLocal<Type> = staticCompositionLocalOf {
    Type(FontFamily.SansSerif)
}

/**
 * Gothic A1 (OFL, 재배포 가능) 을 앱에 넣는다. 다운로더블 폰트를 쓰지 않는 이유는
 * 그게 Play 서비스와 네트워크를 타기 때문이다 — 이 앱은 인터넷 권한이 없다.
 * 폰트 파일이 없으면 시스템 한글 폰트로 떨어진다(무게만 맞춘다).
 */
private fun gothicA1(): FontFamily = runCatching {
    FontFamily(
        Font(R.font.gothic_a1_regular, FontWeight.Normal),
        Font(R.font.gothic_a1_medium, FontWeight.Medium),
        Font(R.font.gothic_a1_bold, FontWeight.Bold),
        Font(R.font.gothic_a1_black, FontWeight.Black),
    )
}.getOrDefault(FontFamily.SansSerif)

@Composable
fun PhotoLogTheme(accent: AccentChoice = AccentChoice.Vermilion, content: @Composable () -> Unit) {
    val palette = Palette(accent = accent.color)
    val type = Type(gothicA1())
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPalette provides palette,
        LocalType provides type,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = palette.accent,
                background = palette.bg,
                surface = palette.surface,
                onBackground = palette.text,
                onSurface = palette.text,
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
