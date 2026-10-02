package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.moalog.personal.work.IndexPhase

/**
 * 설정. 구조(디자인): 기기 내 처리 약속 → 분석 현황 → 분류 항목 토글 → 데이터 관리 → 앱 정보.
 *
 * **디자인 문구를 한 곳 바꿨다.** 원문은 "모델 업데이트만 Wi-Fi에서 내려받습니다"인데 이 앱은 매니페스트에
 * `INTERNET` 권한이 없다(§16). 모델은 APK에 함께 설치되므로 네트워크를 아예 쓰지 않는다 — 사용자가 앱 정보에서 직접
 * 확인할 수 있는 약속이라 문구보다 강하다. 원문대로 가려면 권한을 넣어야 하고, 그러면 이 약속이 깨진다.
 *
 * 되돌릴 수 없는 "분류 결과 전체 삭제"는 눌러도 바로 지우지 않고 대화상자로 한 번 더 묻는다.
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    ui: AppViewModel.Ui,
    toggles: List<CategoryToggle>,
    onFlip: (String) -> Unit,
) {
    val t = theme
    val ctx = LocalContext.current
    val version = remember0 { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-" }

    Column(Modifier.fillMaxSize()) {
        TopRow(height = 56.dp) {
            IconBtn(I.Back, "뒤로", vm::back, modifier = Modifier.padding(start = 4.dp))
            Text("설정", style = fonts.header, color = t.text1, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 기기 내 처리 약속
            Card(Modifier.padding(16.dp), spacing = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(t.accent.copy(alpha = .14f)),
                        contentAlignment = Alignment.Center,
                    ) { Ic(I.Device, size = 24.dp, tint = t.accent) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("기기 내 처리", style = fonts.item, color = t.text1)
                            Box(Modifier.size(8.dp).clip(CircleShape).background(t.ok))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Ic(I.Offline, size = 13.dp, tint = t.text3)
                            Text("네트워크 사용 없음", style = fonts.meta, color = t.text3)
                        }
                    }
                }
                Prose(
                    "사진·영상과 분석 결과는 이 휴대폰을 벗어나지 않습니다. 모델도 앱과 함께 설치되어 네트워크를 쓰지 않습니다.",
                    style = fonts.caption, color = t.text2,
                )
            }

            // 분석 현황
            Card(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), spacing = 8.dp) {
                val c = ui.counts
                Text(
                    "${"%,d".format(c.total)}개 인덱싱 · AI 태그 ${"%,d".format(c.done)}개 · 읽기 실패 ${"%,d".format(c.failed)}개",
                    style = fonts.bodySm.copy(lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text1,
                )
                when {
                    ui.paused != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Ic(I.Pause, size = 16.dp, tint = t.warn)
                        Text(ui.paused, style = fonts.note, color = t.warn, modifier = Modifier.weight(1f))
                        TextBtn("이어서", vm::resumeIndexing, color = t.accent, style = fonts.chip, height = 40.dp, horizontal = 8.dp)
                    }
                    ui.indexing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spinner(size = 28.dp)
                        val p = ui.phaseCounts
                        Text("${if (ui.indexPhase == IndexPhase.VIDEOS) "영상" else "사진"} 분석 중 · ${p.done}/${p.total}개", style = fonts.note, color = t.text2)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ic(I.Check, size = 15.dp, tint = t.ok)
                        Text("분석이 모두 끝났습니다", style = fonts.note, color = t.ok)
                    }
                }
            }

            SectionTitle("분류 항목")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(vertical = 4.dp)) {
                for (tg in toggles) Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp)
                        .toggleable(tg.on, role = Role.Switch) { onFlip(tg.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tg.label, style = fonts.itemMd, color = t.text1)
                        Text(tg.desc, style = fonts.meta.copy(lineHeight = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text3)
                    }
                    MoaSwitch(tg.on)
                }
            }
            Text(
                "끄면 그 분류의 AI 앨범을 숨깁니다. 분석 데이터는 지우지 않습니다.",
                style = fonts.meta.copy(lineHeight = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = t.text3, modifier = Modifier.padding(horizontal = 4.dp),
            )

            SectionTitle("데이터 관리")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(vertical = 4.dp)) {
                ActionRow(I.Refresh, "전체 다시 분석", "촬영 정보는 두고 장면 분류만 다시 합니다", t.text1, t.text2) { vm.reanalyze() }
                Hair(Modifier.padding(horizontal = 16.dp))
                ActionRow(I.Warn, "분류 결과 전체 삭제", "사진·영상은 지우지 않습니다", t.dangerText, t.dangerText) { vm.openDialog(Dialog.DeleteAll) }
            }

            SectionTitle("앱 정보")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(vertical = 4.dp)) {
                InfoRow("버전", version)
                InfoRow("분석 모델", "한국어 CLIP · 기기 내장")
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { vm.openSheet(Sheet.Licenses) }.padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("오픈소스 라이선스", style = fonts.body, color = t.text1, modifier = Modifier.weight(1f))
                    Ic(I.ChevronRight, size = 20.dp, tint = t.text3)
                }
            }
        }
    }
}

@Composable
private fun <T> remember0(calc: () -> T): T = androidx.compose.runtime.remember { calc() }

@Composable
private fun Card(padding: Modifier, spacing: androidx.compose.ui.unit.Dp, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(theme.surface1).then(padding),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = fonts.sectionLabel, color = theme.text3, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
}

@Composable
private fun ActionRow(icon: Int, title: String, note: String, titleColor: Color, iconTint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Ic(icon, size = 22.dp, tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.itemMd, color = titleColor)
            Text(note, style = fonts.meta, color = theme.text3)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = fonts.body, color = theme.text1, modifier = Modifier.weight(1f))
        Text(value, style = fonts.body, color = theme.text3)
    }
}

data class CategoryToggle(val id: String, val label: String, val desc: String, val on: Boolean)
