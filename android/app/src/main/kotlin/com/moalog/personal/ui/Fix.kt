package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * 분류 수정. 잘못 묶인 사진을 골라 다른 앨범으로 옮기거나 이 앨범에서 뺀다.
 *
 * **"이 앨범에서 빼기"는 사진을 지우지 않는다.** 태그만 떼고 사진은 기기에 그대로 둔다 —
 * 두 행동을 한 버튼에 묶으면 사용자가 정리를 무서워하게 된다. 지우는 일은 빨간
 * `휴지통으로` 버튼에만 있다.
 */
@Composable
fun FixScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val album = ui.openAlbum
    val n = ui.selected.size
    val hair = t.hair

    Column(Modifier.fillMaxSize()) {
        TopRow(height = 64.dp) {
            IconBtn(I.Back, "뒤로", vm::back)
            TitleBlock(
                "분류 수정", album?.let { "${it.title} · ${it.count}개" },
                Modifier.weight(1f).padding(start = 4.dp), titleStyle = fonts.header,
            )
        }
        Prose(
            "잘못 묶인 사진·영상을 고르면 다른 앨범으로 옮기거나 이 앨범에서 뺍니다. 어느 쪽이든 사진·영상은 기기에 그대로 남습니다.",
            style = fonts.caption, color = t.text2,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(Radius.thumb)).background(t.surface1).padding(horizontal = 14.dp, vertical = 12.dp),
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ui.fixPhotos.chunked(GRID_COLUMNS), key = { row -> "f:${row.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (p in row) MediaCell(
                        p, Modifier.weight(1f).clickable { vm.toggleSelect(p.id) },
                        mark = CellMark.Select(p.id in ui.selected),
                    )
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // 하단 패널: 고른 개수 + 두 동작. 아무것도 안 골랐으면 흐리게, 눌러도 아무 일 없다.
        Column(
            Modifier.fillMaxWidth().background(t.surface1)
                .drawBehind { drawLine(hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (n > 0) "${n}개 선택" else "잘못 묶인 항목을 고르세요", style = fonts.caption.copy(), color = t.text2)
            Row(Modifier.fillMaxWidth().alpha(if (n > 0) 1f else .4f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    "다른 앨범으로 옮기기", { if (n > 0) vm.openSheet(Sheet.Move) }, Modifier.weight(1f),
                    bg = t.surface3, fg = t.text1, icon = I.Ai, style = fonts.label, horizontal = 8.dp,
                )
                Pill(
                    if (n > 0) "${n}개 이 앨범에서 빼기" else "이 앨범에서 빼기", { if (n > 0) vm.removeSelectedFromAlbum() }, Modifier.weight(1f),
                    bg = t.surface3, fg = t.text1, icon = I.Remove, style = fonts.label, horizontal = 8.dp,
                )
            }
        }
    }
}
