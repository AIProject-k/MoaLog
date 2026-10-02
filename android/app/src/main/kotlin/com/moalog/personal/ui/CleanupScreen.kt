package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.moalog.personal.core.Grouping
import com.moalog.personal.data.MediaKind
import com.moalog.personal.data.PhotoCard

/**
 * 중복 정리. **판단을 돕는 화면이다** — 같은 것끼리 묶어 나란히 보여 주고, 보관할 한 장을 추천하되 이유를 말하며,
 * 보낼 것을 고르면 얼마가 비는지 숫자로 보여 준다.
 *
 *  - `완전히 같은 파일`(바이트까지 같음)과 `비슷한 장면`(연달아 찍음)은 판단의 무게가 달라 말로 구분한다.
 *  - 보관 추천은 **초록 테두리 + 체크 + 이유 문장**이다 — 색만으로 구분하지 않는다.
 *  - 보낼 것은 어둡게 + 선택 체크. 묶음마다 한 장은 코드가 항상 남긴다([Grouping.keepOnePerGroup]).
 *  - `이 묶음은 유지`는 접어 두고(`다시 보기`로 되돌린다) 다음에도 같은 제안을 하지 않는다.
 */
@Composable
fun CleanupScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val trash = LocalActions.current.trash
    val groups = ui.cleanupGroups
    val active = groups.filterNot { it.held }
    val selectable = active.flatMap { g -> g.photos.map(PhotoCard::id) }.toSet()
    val allSelected = selectable.isNotEmpty() && ui.cleanupSelected.containsAll(selectable)
    val (deletable, _) = vm.cleanupCandidates()
    val ids = remember(groups) { groups.flatMap { g -> g.photos.map(PhotoCard::id) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopRow(height = 56.dp) {
                TitleBlock("정리", "겹치는 묶음 ${groups.size}개", Modifier.weight(1f).padding(start = 16.dp, end = 16.dp))
            }
            if (groups.isNotEmpty()) Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.height(44.dp).tap({ vm.selectAllButKept() }, "보관 추천 제외하고 선택").padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Ic(I.Check, size = 18.dp, tint = t.accent)
                    Text("보관 추천 제외하고 선택", style = fonts.bodySmStrong, color = t.accent)
                }
                Box(
                    Modifier.height(44.dp).tap({ vm.toggleCleanupSelectAll() }).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (allSelected) "선택 해제" else "전체 선택", style = fonts.bodySmStrong, color = t.text2) }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (groups.isEmpty()) item(key = "empty") {
                    EmptyState(
                        I.Check, "정리할 것이 없습니다.", top = 120.dp, iconTint = t.ok,
                        description = "겹치는 사진·영상이 생기면 여기에 모입니다.",
                    )
                }
                items(groups, key = { it.key }) { g -> GroupCard(vm, g, ui.cleanupSelected, ids) }
                item(key = "end") { Spacer(Modifier.height(200.dp)) }
            }
        }

        // 하단 바: 보낼 것이 있을 때만. 용량을 제목으로 올렸다.
        if (deletable.isNotEmpty()) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(t.surface1)
                .drawBehind { drawLine(Color(0x12FFFFFF), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${deletable.size}개를 보내면 약 ${vm.sizeText(ui.cleanupBytes)}가 빕니다.", style = fonts.label, color = t.text1)
                Text("묶음마다 한 장은 항상 남습니다.", style = fonts.micro.copy(lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text3)
            }
            Pill("${deletable.size}개 휴지통으로", { trash(deletable) }, bg = t.danger, fg = Color.White, icon = I.Trash, style = fonts.label, horizontal = 16.dp)
        }
    }
}

@Composable
private fun GroupCard(vm: AppViewModel, g: AppViewModel.CleanupGroup, selected: Set<Long>, allIds: List<Long>) {
    val t = theme
    // '완전히 같은 파일'은 지워도 잃는 것이 없고, '비슷한 장면'은 조금씩 다른 사진이라 사람이 봐야 한다.
    val exact = g.group.kind == Grouping.Kind.EXACT
    val unit = if (exact) "개" else "장"
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text((if (exact) "완전히 같은 파일 · " else "비슷한 장면 · ") + g.photos.size + unit, style = fonts.label, color = t.text1)
                Text(if (exact) "내용이 바이트까지 같습니다" else "연달아 찍힌 사진입니다", style = fonts.meta, color = t.text3)
            }
            Box(
                Modifier.offset(x = 4.dp, y = (-6).dp).height(40.dp).tap({ vm.toggleHold(g.key) }, if (g.held) "다시 보기" else "이 묶음은 유지").padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (g.held) "다시 보기" else "이 묶음은 유지", style = fonts.chipSm, color = t.text2) }
        }
        if (g.held) Text("이 묶음은 그대로 둡니다.", style = fonts.note, color = t.text3, modifier = Modifier.offset(y = (-6).dp))
        else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (pair in g.photos.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in pair) Box(Modifier.weight(1f)) { Tile(vm, g, p, p.id in selected, allIds) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 비교 타일 하나. 보관할 것은 초록 테두리 + "보관 추천 · 이유", 보낼 것은 선택 원 + "이걸 보관" 버튼.
 * 사진을 누르면 크게 본다(비교가 판단의 전부라 크게 볼 수 있어야 한다).
 */
@Composable
private fun Tile(vm: AppViewModel, g: AppViewModel.CleanupGroup, p: PhotoCard, selected: Boolean, allIds: List<Long>) {
    val t = theme
    val keep = p.id == g.group.bestId
    val shape = RoundedCornerShape(Radius.tile)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            MediaCell(
                p, Modifier.fillMaxSize().clip(shape).clickable { vm.openViewer(allIds, p.id) }
                    .then(if (keep) Modifier.border(2.dp, t.ok, shape) else Modifier),
                px = 480,
            )
            if (selected && !keep) Box(Modifier.fillMaxSize().clip(shape).background(Color(0x66000000)))
            if (!keep) Box(
                Modifier.align(Alignment.TopEnd).size(48.dp).tap({ vm.toggleCleanupSelect(p.id) }, if (selected) "선택 해제" else "선택"),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(24.dp).background(t.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Ic(I.Check, size = 15.dp, tint = t.onAccent)
                } else Box(Modifier.size(24.dp).background(Color(0x40000000), CircleShape).border(2.dp, Color.White, CircleShape))
            }
        }
        if (keep) Row(Modifier.heightIn(min = 28.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Ic(I.Check, size = 14.dp, tint = t.ok, modifier = Modifier.padding(top = 1.dp))
            Text(
                if (p.id == g.recommendedId) g.keepReason else "보관할 항목",
                style = fonts.metaStrong, color = t.ok,
            )
        } else Pill(
            "이걸 보관", { vm.setKeep(g.key, p.id) }, bg = t.surface3, fg = t.text1, height = 32.dp,
            style = fonts.metaStrong, horizontal = 12.dp,
        )
        val info = g.info[p.id]
        if (info != null) Text(
            listOfNotNull(info.bucket, vm.sizeText(info.bytes)).joinToString(" · "),
            style = fonts.micro.copy(lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = t.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

