package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp

/**
 * 분류 수정. 잘못 묶인 사진을 골라 다른 앨범으로 옮기거나 이 앨범에서 뺀다.
 *
 * **"이 앨범에서 빼기"는 사진을 지우지 않는다.** 태그만 떼고 사진은 기기에 그대로 둔다 —
 * 두 행동을 한 버튼에 묶으면 사용자가 정리를 무서워하게 된다. 지우는 일은 빨간
 * `휴지통으로` 버튼에만 있다.
 */
@Composable
fun FixScreen(vm: AppViewModel, ui: AppViewModel.Ui, columns: Int = 3) {
    val t = theme
    val n = ui.selected.size
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton { vm.back() }
            Text("분류 수정", style = fonts.header, color = t.text, modifier = Modifier.weight(1f))
            Text(if (n > 0) "${n}장 선택" else "사진을 고르세요",
                style = fonts.meta, color = t.text.copy(alpha = .42f))
        }
        Text(
            "잘못 묶인 사진을 고르면 다른 앨범으로 옮기거나 이 앨범에서 뺍니다. " +
                "어느 쪽이든 사진은 기기에 그대로 남습니다.",
            style = fonts.chip.copy(lineHeight = TextUnit(21.25f, TextUnitType.Sp)),
            color = t.textMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            items(ui.fixPhotos.chunked(columns)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (p in row) {
                        val on = p.id in ui.selected
                        Box(Modifier.weight(1f).aspectRatio(1f).clickable { vm.toggleSelect(p.id) }) {
                            Thumb(p.uri, Modifier.matchParentSize())
                            if (on) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)))
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (on) t.accent else Color.Black.copy(alpha = .25f)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Text("✓", style = fonts.badge, color = Color.White) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(t.bg).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("다른 앨범으로 옮기기")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(vm.moveTargets()) { label ->
                    Box(
                        Modifier.clip(RoundedCornerShape(Radius.card)).background(t.surface4)
                            .clickable { vm.moveSelected(label) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) { Text(label, style = fonts.chip, color = t.text.copy(alpha = .8f)) }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
                    .background(if (n > 0) t.accent else t.surface4)
                    .then(if (n > 0) Modifier.clickable { vm.removeSelectedFromAlbum() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (n > 0) "${n}장 이 앨범에서 빼기" else "고른 사진 없음",
                    style = fonts.itemTitle,
                    color = if (n > 0) Color.White else t.textFaint,
                )
            }
        }
    }
}
