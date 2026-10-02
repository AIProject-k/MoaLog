package com.moalog.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/**
 * 첫 분석 화면. 사진 단계에서만 이 화면이 보이고, 사진 분석이 끝나면 갤러리가 열리고 영상은 뒤에서 이어진다.
 *
 * 진행률은 **실제 워커에서 온다** — 애니메이션이 아니다. 디자인 프로토타입은 타이머가 올렸지만, 여기서 가짜 진행률을
 * 보여 주면 사용자가 남은 시간을 판단할 수 없다. Pass 1은 싸고 Pass 2가 대부분이라 진행이 고르지 않은데,
 * 그 불규칙함이 정직한 정보다. 뜨겁거나 배터리가 낮아 멈췄으면 링이 회색이 되고 이유와 "이어서"가 보인다.
 */
@Composable
fun AnalyzingScreen(progress: Int, done: Int, total: Int, paused: String?, onResume: () -> Unit) {
    val t = theme
    val nf = NumberFormat.getIntegerInstance(Locale.KOREA)
    val ring = if (paused != null) t.hint else t.accent
    val track = t.surface2

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
        Text("MoaLog", style = fonts.item.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text1)

        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 40.dp).size(200.dp)) {
            Canvas(Modifier.size(200.dp)) {
                val s = 10.dp.toPx()
                val arc = Size(size.width - s, size.height - s)
                drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s))
                drawArc(ring, -90f, 360f * progress.coerceIn(0, 100) / 100f, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$progress%", style = fonts.progress, color = t.text1)
                Text("전체 ${nf.format(total)}장 중 ${nf.format(done)}장", style = fonts.note.copy(lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text2)
            }
        }

        if (paused != null) Row(
            Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.thumb)).background(t.surface1)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Ic(I.Pause, size = 20.dp, tint = t.warn)
            Text(paused, style = fonts.bodySmStrong, color = t.text1, modifier = Modifier.weight(1f))
            TextBtn("이어서", onResume, color = t.accent, style = fonts.chip, height = 40.dp, horizontal = 12.dp)
        }

        // 단계 목록. 대기 → 진행 → 완료. 멈췄으면 진행 중이던 단계가 "멈춤"이 된다.
        Column(
            Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(vertical = 4.dp),
        ) {
            for (step in AnalyzeStep.ordered) {
                val state = step.stateAt(progress)
                Row(
                    Modifier.height(52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when (state) {
                            StepState.Done -> Ic(I.Check, size = 20.dp, tint = t.ok)
                            StepState.Running -> Box(Modifier.size(16.dp).border(2.dp, t.accent, RoundedCornerShape(6.dp)))   // 디자인: 안쪽 12 + 테두리 2 → 바깥 16의 둥근 사각
                            StepState.Waiting -> Box(Modifier.size(6.dp).background(t.hint, CircleShape))
                        }
                    }
                    Text(
                        step.label, style = fonts.body,
                        color = when (state) { StepState.Done -> t.ok; StepState.Running -> t.text1; StepState.Waiting -> t.text3 },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when (state) {
                            StepState.Done -> "완료"
                            StepState.Running -> if (paused != null) "멈춤" else "진행 중"
                            StepState.Waiting -> "대기"
                        },
                        style = fonts.meta, color = t.text3,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ic(I.Info, size = 16.dp, tint = t.text3, modifier = Modifier.padding(top = 2.dp))
            Prose("화면을 닫아도 백그라운드에서 이어집니다. 충전 중일 때 더 빠르게 처리됩니다.", style = fonts.note, color = t.text3)
        }
    }
}
