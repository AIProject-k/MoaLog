package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 첫 화면. 권한을 요청하기 **전에** 왜 필요한지와 무엇을 하지 않는지(업로드 없음)를 말한다.
 *
 * "기기 안에서만 처리"가 문구만이 아니라 그림으로도 읽히게 했다: 사진 격자 카드 + 아래에 걸친
 * "이 휴대폰 안에서만" 배지 + "외부로 보내지 않습니다" 점선 줄.
 *
 * @param later "나중에 할게요"를 눌렀는가. 누르면 허용하기 전에는 갤러리가 비어 있다는 안내가 뜨고 "나중에" 버튼은 사라진다
 *  (디자인의 `Permission/later`).
 */
@Composable
fun OnboardingScreen(later: Boolean, onGrant: () -> Unit, onLater: () -> Unit) {
    val t = theme
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
        Wordmark()

        // 격자 카드
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 36.dp).size(228.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(36.dp)).background(t.surface1).border(1.dp, t.ring, RoundedCornerShape(36.dp)))
            Column(Modifier.padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((r, row) in TILES.chunked(3).withIndex()) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((c, tone) in row.withIndex()) Box(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(8.dp))
                            .softGradient(Color(tone.first), Color(tone.second), r * 3 + c),
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 0.dp).offset(y = 18.dp).height(36.dp)
                    .clip(RoundedCornerShape(18.dp)).background(t.accent).padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Ic(I.Device, size = 18.dp, tint = t.onAccent)
                Text("이 휴대폰 안에서만", style = fonts.chipSm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = t.onAccent, maxLines = 1)
            }
        }

        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(top = 34.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashedLine()
            Ic(I.Offline, size = 18.dp, tint = t.text3)
            Text("외부로 보내지 않습니다", style = fonts.meta, color = t.text3)
            DashedLine()
        }

        Prose("쌓아둔 사진·영상, AI가 알아서 정리합니다", style = fonts.display, color = t.text1, modifier = Modifier.padding(top = 28.dp))
        Prose(
            "인물, 장소, 날짜, 화면 캡처까지 구분해 앨범을 만들어 둡니다. 분석은 기기 안에서만 처리되고 사진·영상은 어디에도 올라가지 않습니다.",
            style = fonts.body, color = t.text2, modifier = Modifier.padding(top = 10.dp),
        )
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bullet(I.Offline, "기기 내 처리 · 네트워크는 지도를 열 때만 사용")
            Bullet(I.Undo, "분류가 틀리면 바로 고칠 수 있고, 다음부터 반영됩니다")
        }

        Spacer(Modifier.weight(1f))
        if (later) Text(
            "나중에 허용해도 됩니다. 허용하기 전에는 갤러리가 비어 있습니다.",
            style = fonts.note, color = t.text2,
            modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.thumb)).background(t.surface1)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Pill("사진·동영상 접근 허용하고 시작", onGrant, Modifier.fillMaxWidth(), height = 52.dp, style = fonts.button)
        if (!later) TextBtn("나중에 할게요", onLater, Modifier.align(Alignment.CenterHorizontally).fillMaxWidth().padding(top = 6.dp), color = t.text2, style = fonts.label)
    }
}

/** 둥근 사각 안에 2×2 칸(셋은 밝게, 하나는 강조색) + "MoaLog". */
@Composable
private fun Wordmark() {
    val t = theme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(t.surface1).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(1.5.dp)).background(t.text1))
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(1.5.dp)).background(t.text1))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(1.5.dp)).background(t.text1))
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(t.accent))
            }
        }
        Text("MoaLog", style = fonts.item.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text1)
    }
}

@Composable
private fun DashedLine() {
    val c = theme.hint
    Box(Modifier.width(40.dp).height(1.dp).drawBehind {
        drawLine(c, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    })
}

@Composable
private fun Bullet(icon: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ic(icon, size = 20.dp, tint = theme.accent)
        Text(text, style = fonts.bodySm.copy(lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)), color = theme.text1)
    }
}

/**
 * 디자인의 자리표시 사진과 같은 모양: 비스듬한 두 색 그라데이션 위에 흐릿한 흰 빛 한 점.
 * 각도와 빛의 위치는 칸 번호로 정해서 칸마다 조금씩 다르다.
 */
private fun Modifier.softGradient(from: Color, to: Color, i: Int): Modifier = drawBehind {
    val rad = Math.toRadians((125 + (i * 37) % 70).toDouble())
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())            // CSS처럼 0도가 위쪽, 시계 방향
    val half = (abs(size.width * dir.x) + abs(size.height * dir.y)) / 2
    val mid = Offset(size.width / 2, size.height / 2)
    drawRect(Brush.linearGradient(listOf(from, to), mid - dir * half, mid + dir * half))

    val spot = Offset(size.width * (18 + (i * 29) % 55) / 100f, size.height * (15 + (i * 17) % 45) / 100f)
    val far = maxOf(
        spot.getDistance(), Offset(size.width - spot.x, spot.y).getDistance(),
        Offset(spot.x, size.height - spot.y).getDistance(), Offset(size.width - spot.x, size.height - spot.y).getDistance(),
    )
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .3f), Color.White.copy(alpha = 0f)), spot, far * .55f))
}

/** 디자인이 정한 9칸 색(바다·음식·강아지·문서·잎·밤·노을·카페·도시). */
private val TILES = listOf(
    0xFF9CC0D6 to 0xFF3C6A86, 0xFFE0A877 to 0xFF7B3E22, 0xFFC2A283 to 0xFF5A4431,
    0xFFECE9E2 to 0xFFACA79C, 0xFFA3BF8F to 0xFF44603A, 0xFF46507A to 0xFF141830,
    0xFFEDB2A0 to 0xFF6C4B77, 0xFFD8BFA3 to 0xFF6E5641, 0xFF9492A3 to 0xFF393848,
)
