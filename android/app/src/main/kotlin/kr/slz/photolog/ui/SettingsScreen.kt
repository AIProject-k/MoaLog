package kr.slz.photolog.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 자동분류 설정. 디자인의 구조를 지킨다: 상태 배지 → 분류 항목 토글 → 파괴적 동작.
 *
 * **디자인 문구를 한 곳 바꿨다.** 원문은 "모델 업데이트만 Wi-Fi에서 내려받습니다"인데
 * 이 앱은 매니페스트에 `INTERNET` 권한이 없다(§16). 모델은 APK에 함께 설치되므로
 * 네트워크를 아예 쓰지 않는다 — 사용자가 앱 정보에서 직접 확인할 수 있는 약속이라
 * 문구보다 강하다. 원문대로 가려면 권한을 넣어야 하고, 그러면 이 약속이 깨진다.
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    ui: AppViewModel.Ui,
    toggles: List<CategoryToggle>,
    onFlip: (String) -> Unit,
    onTrash: () -> Unit,
) {
    val t = theme
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("자동분류 설정", style = fonts.screenTitle, color = t.text,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp))
        }

        item {
            Column(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card)).background(t.surface)
                    .padding(horizontal = 18.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(t.ok))
                    Text(if (ui.indexing) "분석 중" else "기기 내 처리", style = fonts.bodySm.copy(fontWeight = FontWeight.Bold), color = t.text)
                }
                Text(
                    "사진과 분석 결과는 이 휴대폰을 벗어나지 않습니다. " +
                        "모델도 앱과 함께 설치되어 네트워크를 쓰지 않습니다.",
                    style = fonts.chip.copy(lineHeight = androidx.compose.ui.unit.TextUnit(
                        21.25f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = t.textMuted,
                )
            }
        }

        item {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp).fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${vm.totalText()}장 인덱싱", style = fonts.bodySm.copy(fontWeight = FontWeight.Bold), color = t.text)
                    Text(
                        "AI 태그 ${ui.counts.done}장 · 읽기 실패 ${ui.counts.failed}장",
                        style = fonts.meta, color = t.textMuted,
                    )
                }
            }
        }

        item { SectionLabel("분류 항목", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)) }
        items(toggles.size, key = { toggles[it].id }) { i ->
            val g = toggles[i]
            Row(
                Modifier.fillMaxWidth().clickable { onFlip(g.id) }
                    .padding(horizontal = 20.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(g.label, style = fonts.body.copy(fontWeight = FontWeight.Medium), color = t.text)
                    Text(g.desc, style = fonts.meta, color = t.text.copy(alpha = .42f))
                }
                Switch(g.on)
            }
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(t.divider))
        }

        item {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.cleanupCount > 0) ActionRow(
                    "중복 사진 ${ui.cleanupCount}장 휴지통으로",
                    "그룹마다 가장 잘 나온 한 장은 남깁니다",
                    t.accentHi, onTrash,
                )
                ActionRow("전체 다시 분석", "촬영 정보는 두고 장면 분류만 다시 합니다", t.text) { vm.reanalyze() }
                ActionRow("분류 결과 전체 삭제", "사진은 지우지 않습니다", t.accentHi) { vm.clearAnalysis() }
            }
        }
    }
}

data class CategoryToggle(val id: String, val label: String, val desc: String, val on: Boolean)

@Composable
private fun ActionRow(title: String, note: String, color: Color, onClick: () -> Unit) {
    val t = theme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).background(t.surface2)
            .clickable(onClick = onClick).padding(horizontal = 17.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = fonts.bodySm.copy(fontWeight = FontWeight.Medium), color = color)
        Text(note, style = fonts.meta, color = t.textFaint)
    }
}

/** 디자인의 토글. 46×27 트랙, 21 노브, 19dp 이동. */
@Composable
private fun Switch(on: Boolean) {
    val t = theme
    val offset by animateDpAsState(if (on) 19.dp else 0.dp, label = "knob")
    Box(
        Modifier.size(46.dp, 27.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) t.accent else t.surfaceHi).padding(3.dp),
    ) {
        Box(
            Modifier.offset(x = offset).size(21.dp).clip(CircleShape)
                .background(if (on) Color.White else t.text.copy(alpha = .55f))
        )
    }
}
