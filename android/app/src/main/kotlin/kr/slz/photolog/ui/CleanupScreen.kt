package kr.slz.photolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * 정리. **지우라고 권하는 화면이 아니라 비교하고 판단하는 화면이다.**
 *
 * 예전 화면은 "가장 잘 나온 사진"이라고 적어 두고 3열 썸네일을 늘어놓았다. 두 가지가
 * 틀렸다: 선명도가 높다고 잘 나온 사진이 아니고(눈을 감았을 수 있다), 손톱만 한
 * 썸네일로는 어느 쪽이 나은지 애초에 알 수 없다. 그래서
 *  - 2열로 크게 놓고 눌러서 전체 화면 비교로 넘어가고
 *  - 추천 이유를 그대로 적고(`가장 선명함`), 보관할 사진을 사용자가 바꿀 수 있고
 *  - 정리하면 얼마가 비는지 숫자로 보여 주고
 *  - `이 묶음은 유지`로 같은 제안을 그만 받을 수 있게 했다.
 */
@Composable
fun CleanupScreen(vm: AppViewModel, ui: AppViewModel.Ui, columns: Int = 2) {
    val t = theme
    val trash = LocalActions.current.trash
    val selected = ui.cleanupSelected
    val all = ui.cleanupGroups.flatMap { it.photos }.map(PhotoCard::id).toSet()
    val allSelected = all.isNotEmpty() && selected.containsAll(all)
    val (deletable, _) = vm.cleanupCandidates()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("정리", style = fonts.screenTitle, color = t.text)
                Text(
                    if (ui.cleanupGroups.isEmpty()) "겹치는 사진이 없습니다"
                    else "겹치는 묶음 ${ui.cleanupGroups.size}개",
                    style = fonts.meta, color = t.textMuted,
                )
            }
        }

        if (ui.cleanupGroups.isNotEmpty()) Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Chip("보관 추천 제외하고 선택") { vm.selectAllButKept() }
            Chip(if (allSelected) "선택 해제" else "전체 선택") { vm.toggleCleanupSelectAll() }
        }

        if (ui.cleanupGroups.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("정리할 것이 없습니다.", style = fonts.itemTitle, color = t.textBody)
                    Text("겹치는 사진이 생기면 여기에 모입니다.", style = fonts.caption, color = t.textFaint)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(ui.cleanupGroups, key = { it.key }) { group ->
                    CleanupGroupCard(vm, group, selected, columns)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(t.bg)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                if (deletable.isEmpty()) "묶음마다 한 장은 코드가 강제로 남깁니다."
                else "${deletable.size}장을 보내면 약 ${vm.sizeText(ui.cleanupBytes)}가 빕니다.",
                style = fonts.meta, color = t.textMuted,
            )
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
                    .background(if (deletable.isNotEmpty()) t.accentHi else t.surface4)
                    .then(if (deletable.isNotEmpty()) Modifier.clickable { trash(deletable) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (deletable.isNotEmpty()) "${deletable.size}장 휴지통으로" else "사진을 선택하세요",
                    style = fonts.itemTitle,
                    color = if (deletable.isNotEmpty()) Color.White else t.textFaint,
                )
            }
        }
    }
}

@Composable
private fun CleanupGroupCard(
    vm: AppViewModel,
    group: AppViewModel.CleanupGroup,
    selected: Set<Long>,
    columns: Int,
) {
    val t = theme
    // '완전히 같은 파일'과 '비슷한 장면'은 판단의 무게가 다르다. 앞은 지워도 잃는 것이
    // 없고, 뒤는 조금씩 다른 사진이라 사람이 봐야 한다. 그러니 말로 구분한다.
    val exact = group.group.kind == Grouping.Kind.EXACT
    val ids = group.photos.map(PhotoCard::id)
    Column(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (exact) "완전히 같은 파일" else "비슷한 장면",
                    style = fonts.body.copy(fontWeight = FontWeight.Bold), color = t.text,
                )
                Text(
                    if (exact) "${group.photos.size}장 · 내용이 바이트까지 같습니다"
                    else "${group.photos.size}장 · 연달아 찍힌 사진입니다",
                    style = fonts.meta, color = t.textMuted,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(15.dp)).background(t.surface3)
                    .clickable { vm.dismissGroup(group.key) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("이 묶음은 유지", style = fonts.meta, color = t.text.copy(alpha = .75f)) }
        }

        for (row in group.photos.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (photo in row) CompareTile(
                    photo = photo,
                    keep = photo.id == group.group.bestId,
                    keepReason = group.keepReason,
                    selected = photo.id in selected,
                    onOpen = { vm.openViewer(ids, photo.id) },
                    onToggle = { vm.toggleCleanupSelect(photo.id) },
                    onKeep = { vm.setKeep(group.key, photo.id) },
                    modifier = Modifier.weight(1f),
                )
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 비교용 한 칸. 3열 손톱만 한 썸네일이 아니라 2열이고, 눌러서 전체 화면으로 확대해
 * 볼 수 있다 — 표정과 눈 감음은 이 크기에서도 잘 안 보이기 때문이다.
 */
@Composable
private fun CompareTile(
    photo: PhotoCard,
    keep: Boolean,
    keepReason: String,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier,
) {
    val t = theme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radius.thumb))
                .then(if (keep) Modifier.border(2.dp, t.ok, RoundedCornerShape(Radius.thumb)) else Modifier)
                .clickable(onClick = onOpen),
        ) {
            Thumb(photo.uri, Modifier.matchParentSize(), px = 640)
            if (selected) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .38f)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape)
                    .background(if (selected) t.accent else Color.Black.copy(alpha = .32f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) { Text(if (selected) "✓" else "", style = fonts.chip, color = Color.White) }
        }
        if (keep) Text(keepReason, style = fonts.badge, color = t.ok, maxLines = 2)
        else Text(
            "이 사진 보관", style = fonts.meta, color = t.text.copy(alpha = .55f),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onKeep)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
