package kr.slz.photolog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/**
 * 첫 분석 화면. 디자인의 conic-gradient 링 + 단계 목록.
 *
 * 진행률은 **실제 워커에서 온다** — 애니메이션이 아니다. 디자인 프로토타입은 70ms마다
 * 2%씩 올리는 타이머였지만, 여기서 가짜 진행률을 보여 주면 사용자가 남은 시간을
 * 판단할 수 없다. Pass 1은 싸고 Pass 2가 대부분이라 진행이 고르지 않은데,
 * 그 불규칙함이 정직한 정보다.
 */
@Composable
fun AnalyzingScreen(progress: Int, done: Int, total: Int) {
    val t = theme
    val nf = NumberFormat.getIntegerInstance(Locale.KOREA)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Text("STEP 1 / 1 · 첫 분석", style = fonts.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.82f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.textFaint)

        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                ProgressRing(progress)
                Column(
                    Modifier.padding(14.dp).fillMaxSize().clip(CircleShape).background(t.bg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("$progress%", style = fonts.progress, color = t.text)
                    Spacer(Modifier.height(4.dp))
                    Text("분석 중", style = fonts.meta, color = t.text.copy(alpha = .45f))
                }
            }
            Text(
                "전체 ${nf.format(total)}장 중 ${nf.format(done)}장",
                style = fonts.body, color = t.text.copy(alpha = .6f),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            for (step in AnalyzeStep.ordered) {
                val state = step.stateAt(progress)
                val active = state != StepState.Waiting
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(if (active) t.surface4 else t.surface2)
                        .alpha(if (active) 1f else .45f)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(step.label, style = fonts.caption, color = t.text.copy(alpha = .85f), modifier = Modifier.weight(1f))
                    Text(state.label, style = fonts.meta.copy(fontWeight = FontWeight.Bold), color = t.accent)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "화면을 닫아도 백그라운드에서 이어집니다. 충전 중일 때 더 빠르게 처리됩니다.",
            style = fonts.meta.copy(lineHeight = androidx.compose.ui.unit.TextUnit(20.4f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = t.textFaint,
        )
    }
}

/** 디자인의 `conic-gradient(accent p%, #22222A 0)`. Compose에는 conic이 없어 호를 그린다. */
@Composable
private fun ProgressRing(progress: Int) {
    val t = theme
    Canvas(Modifier.fillMaxSize()) {
        val w = 14.dp.toPx()
        val inset = w / 2
        val arc = Size(size.width - w, size.height - w)
        drawArc(
            color = t.surface5, startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arc,
            style = Stroke(width = w),
        )
        drawArc(
            color = t.accent, startAngle = -90f, sweepAngle = 360f * progress / 100f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arc,
            style = Stroke(width = w),
        )
    }
}
