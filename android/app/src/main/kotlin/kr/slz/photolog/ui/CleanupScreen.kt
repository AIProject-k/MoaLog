package kr.slz.photolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.slz.photolog.core.Grouping
import kr.slz.photolog.data.PhotoCard

/** 중복 사진을 비교하고 고르는 일반 갤러리형 정리 화면. */
@Composable
fun CleanupScreen(vm: AppViewModel, ui: AppViewModel.Ui, onTrash: () -> Unit, columns: Int = 3) {
    val t = theme
    val selected = ui.cleanupSelected
    val all = ui.cleanupGroups.flatMap { it.photos }.map(PhotoCard::id).toSet()
    val allSelected = all.isNotEmpty() && selected.containsAll(all)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton { vm.back() }
            Column(Modifier.weight(1f)) {
                Text("중복 사진 정리", style = fonts.header, color = t.text)
                Text(
                    if (selected.isEmpty()) "겹치는 사진을 골라 휴지통으로 보낼 수 있어요"
                    else "${selected.size}장 선택됨",
                    style = fonts.meta, color = t.textMuted,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(Radius.chip)).background(t.surface3)
                    .clickable { vm.toggleCleanupSelectAll() }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(if (allSelected) "선택 해제" else "전체 선택", style = fonts.chip, color = t.text) }
        }

        if (ui.cleanupGroups.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("현재 발견된 중복 사진이 없습니다.", style = fonts.body, color = t.textMuted)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(ui.cleanupGroups, key = { it.group.kind.name + it.group.ids.first() }) { group ->
                    CleanupGroupCard(group, selected, vm::toggleCleanupSelect, columns)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(t.bg).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("한 그룹을 모두 골라도 가장 잘 나온 한 장은 자동으로 남깁니다.", style = fonts.meta, color = t.textMuted)
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
                    .background(if (selected.isNotEmpty()) t.accentHi else t.surface4)
                    .then(if (selected.isNotEmpty()) Modifier.clickable(onClick = onTrash) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected.isNotEmpty()) "${selected.size}장 휴지통으로" else "사진을 선택하세요",
                    style = fonts.itemTitle,
                    color = if (selected.isNotEmpty()) Color.White else t.textFaint,
                )
            }
        }
    }
}

@Composable
private fun CleanupGroupCard(
    group: AppViewModel.CleanupGroup,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    columns: Int,
) {
    val t = theme
    val label = if (group.group.kind == Grouping.Kind.EXACT) "완전히 같은 사진" else "비슷한 연속 사진"
    Column(Modifier.padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = fonts.body.copy(fontWeight = FontWeight.Bold), color = t.text)
            Text("${group.photos.size}장", style = fonts.meta, color = t.textMuted)
        }
        for (row in group.photos.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (photo in row) CleanupThumb(
                    photo, photo.id == group.group.bestId, photo.id in selected, onToggle,
                    Modifier.weight(1f),
                )
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CleanupThumb(
    photo: PhotoCard,
    keep: Boolean,
    selected: Boolean,
    onToggle: (Long) -> Unit,
    modifier: Modifier,
) {
    val t = theme
    Box(modifier.aspectRatio(1f).clickable { onToggle(photo.id) }) {
        Thumb(photo.uri, Modifier.matchParentSize())
        if (selected) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)))
        if (keep) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(t.ok).padding(horizontal = 6.dp, vertical = 3.dp),
            ) { Text("남길 사진", style = fonts.badge, color = Color.White) }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                .background(if (selected) t.accent else Color.Black.copy(alpha = .28f)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", style = fonts.badge, color = Color.White) }
    }
}
