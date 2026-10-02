package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moalog.personal.data.PhotoCard

/*
 * 사진 탭. **분류를 몰라도 사진에 닿을 수 있어야 한다** — 앱을 열면 바로 사진이 보이는 갤러리다.
 *
 * 디자인(`MoaLog App.dc.html`)의 구성: 제목줄 56dp(제목·부제·검색·설정) 하나만 고정이고, 그 아래
 * 필터·모아보기·일부 허용 안내는 **목록과 함께 스크롤되어 접힌다**. 그래서 스크롤 전 첫 화면에서도 그리드가
 * 화면의 60% 이상이다(현재 디자인의 문제 3).
 */
@Composable
fun PhotosScreen(vm: AppViewModel, ui: AppViewModel.Ui, onAllowAll: () -> Unit) {
    val actions = LocalActions.current
    val state = rememberLazyListState()
    val rows = remember(ui.photoDays) { buildRows(ui.photoDays) }
    val ids = remember(ui.photoDays) { ui.photoDays.flatMap { it.photos }.map(PhotoCard::id) }
    val selecting = ui.selectionMode

    // 월로 건너뛰면 목록이 그 달부터 새로 읽혀 온다. 보던 자리를 버리고 그 달의 첫 날짜 머리글이 맨 위에 오게 한다.
    // 머리글 앞에 있는 줄(일부 허용 안내 · 필터 · 모아보기)의 개수만큼 건너뛴다.
    // 이미 처리한 건너뛰기 번호를 기억해 둔다 — 화면에 다시 들어올 때(사진을 열었다 닫을 때) 같은 건너뛰기를 또 하면
    // 방금 돌려 놓은 스크롤 자리를 덮어쓴다.
    val gridStart = (if (ui.limitedAccess) 1 else 0) + 1 +
        (if (ui.mediaFilter == "all" && (ui.memories.isNotEmpty() || ui.cleanupCount > 0)) 1 else 0)
    var handledJump by rememberSaveable { mutableIntStateOf(ui.jumpToken) }
    LaunchedEffect(ui.jumpToken) {
        if (ui.jumpToken != handledJump) { handledJump = ui.jumpToken; state.scrollToItem(gridStart) }
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) SelectionTopBar(
            title = if (ui.selected.isEmpty()) "사진·영상을 선택하세요" else "${ui.selected.size}개 선택",
            sub = selectionSub(ui.selected, ids.size),
            allSelected = ui.visibleIds.isNotEmpty() && ui.selected.containsAll(ui.visibleIds),
            onCancel = vm::cancelSelection, onSelectAll = vm::toggleSelectAll,
        ) else TopRow(height = 56.dp) {
            TitleBlock("갤러리", photosSubtitle(ui), Modifier.weight(1f).padding(start = 16.dp))
            IconBtn(I.Search, "검색", { vm.openSearch() })
            IconBtn(I.Settings, "설정", { vm.go(Screen.Settings) })
            Spacer(Modifier.width(4.dp))
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                if (ui.limitedAccess) item(key = "limited") { LimitedAccessBanner(onAllowAll) }
                item(key = "segment") {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaSegment(ui.mediaFilter, vm::setMediaFilter)
                    }
                }
                // 영상만 보는 중에는 모아보기를 접는다(디자인) — 영상만 남기는 필터에 사진 모음이 섞이면 안 된다.
                if (ui.mediaFilter == "all" && (ui.memories.isNotEmpty() || ui.cleanupCount > 0)) item(key = "moa") {
                    MoaRow(ui.memories, ui.cleanupCount, vm::openMemory) { vm.go(Screen.Cleanup) }
                }
                gridRows(
                    rows = rows, selecting = selecting, selected = ui.selected, favorites = ui.favorites,
                    onOpen = { vm.openViewer(ids, it) },
                    onToggle = vm::toggleSelect,
                    onLongPress = { if (selecting) vm.toggleSelect(it) else vm.startSelection(it) },
                )
                if (ui.photoDays.isEmpty() && !ui.loadingMore) item(key = "empty") { PhotosEmpty(ui) }
                item(key = "end") { Spacer(Modifier.height(120.dp)) }
            }
            LoadMoreWhenNearEnd(state) { vm.loadPhotos() }

            // 스크러버: 한참 내려가면 지금 어느 달인지 알려 주고, 누르면 월별 이동 시트가 열린다.
            val month = monthAtTop(state, rows)
            val scrolled = state.firstVisibleItemIndex >= 3
            if (!selecting && scrolled && month != null && ui.photoTotal > 12) MonthScrubber(
                monthLabel(month), Modifier.align(Alignment.TopEnd),
            ) { vm.openSheet(Sheet.Month) }

            // 분석 진행. 알림이 아니라 갤러리 안에서 조용히 보인다(디자인 결정: 비침습 진행 표시).
            if (!selecting && ui.indexing && ui.phaseCounts.total > 0 && ui.phaseCounts.done < ui.phaseCounts.total)
                ProgressPill(
                    ui.workMessage.ifBlank { "분석 중" },
                    ui.phaseCounts.done.toFloat() / ui.phaseCounts.total,
                    Modifier.align(Alignment.BottomCenter),
                )
        }

        if (selecting) {
            val n = ui.selected.size
            SelectionBottomBar(
                actions = selectionActions(vm, ui, album = null),
                trashLabel = if (n > 0) "${n}개 휴지통으로" else "휴지통으로",
                trashEnabled = n > 0,
                onTrash = { actions.trash(ui.selected) },
            )
        }
    }
}


private fun photosSubtitle(ui: AppViewModel.Ui): String {
    val total = ui.counts.total
    return when {
        total == 0 -> "0개"
        ui.counts.done < total -> "${"%,d".format(total)}개 · AI 분류 ${"%,d".format(ui.counts.done)}개 완료"
        else -> "${"%,d".format(total)}개"
    }
}

@Composable
private fun PhotosEmpty(ui: AppViewModel.Ui) {
    when {
        ui.limitedAccess -> EmptyState(
            I.Warn, "허용된 사진·영상이 없습니다",
            description = "위의 '전체 허용'으로 사진과 영상 접근을 모두 허용하면 앨범이 돌아옵니다.",
        )
        ui.indexing && ui.counts.total == 0 -> EmptyState(I.Photo, "사진·영상을 읽고 있습니다")
        ui.mediaFilter == "video" -> EmptyState(I.Video, "동영상이 없습니다.")
        ui.mediaFilter == "image" -> EmptyState(I.Photo, "사진이 없습니다.")
        else -> EmptyState(
            I.Photo, "아직 사진·영상이 없습니다",
            description = "카메라로 찍거나 저장한 사진·영상이 여기에 모입니다.",
        )
    }
}

// ---------------------------------------------------------------- 조각

/**
 * 미디어 접근이 일부만 허용됐을 때의 안내. 안드로이드가 실제로 만드는 상태다 — 그냥 두면 앨범이 왜 비어
 * 있는지 알 수 없다. 목록 맨 위에 **흐름 안에** 둔다(덮어 얹으면 아래 요소가 비쳐 글자가 겹친다).
 */
@Composable
private fun LimitedAccessBanner(onAllowAll: () -> Unit) {
    val t = theme
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card)).background(t.surface1)
            .border(1.dp, t.ring, RoundedCornerShape(Radius.card)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ic(I.Warn, size = 20.dp, tint = t.warn)
            Text("미디어 접근이 일부만 허용됐습니다", style = fonts.label, color = t.text1)
        }
        Prose(
            "사진과 동영상 중 허용한 항목만 분석합니다. 전체 갤러리를 보려면 두 권한을 모두 허용해 주세요.",
            style = fonts.note, color = t.text2,
        )
        Pill(
            "전체 허용", onAllowAll, bg = t.surface3, fg = t.accent, height = 40.dp,
            style = fonts.bodySmStrong, horizontal = 16.dp,
        )
    }
}

/** 모아보기 카드(이번 주·N년 전 오늘) 몇 장 + 정리 제안 카드. 한 줄로 가로 스크롤한다. */
@Composable
private fun MoaRow(
    memories: List<AppViewModel.Memory>,
    cleanupCount: Int,
    onOpen: (AppViewModel.Memory) -> Unit,
    onClean: () -> Unit,
) {
    // 키를 주지 않는다. 키가 있으면 카드가 앞에 끼어들 때 줄이 원래 첫 카드를 붙들고 밀려, 새 카드가 왼쪽 밖에 숨는다.
    // 모아보기는 앱을 켠 뒤에 늦게 읽혀 오기 때문에 늘 겪는 일이다.
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(memories) { m -> MoaCard(m) { onOpen(m) } }
        if (cleanupCount > 0) item { CleanCard(cleanupCount, onClean) }
    }
}

@Composable
private fun MoaCard(m: AppViewModel.Memory, onClick: () -> Unit) {
    Box(Modifier.width(132.dp).height(76.dp).clip(RoundedCornerShape(Radius.thumb)).clickable(onClick = onClick)) {
        PlainThumb(m.photos.firstOrNull()?.uri, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, .25f to Color.Transparent, 1f to Color(0xB3000000)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
            Text(m.title, style = fonts.chipSm, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.note, style = fonts.badge.copy(fontWeight = FontWeight.Normal), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CleanCard(count: Int, onClick: () -> Unit) {
    val t = theme
    Column(
        Modifier.width(132.dp).height(76.dp).clip(RoundedCornerShape(Radius.thumb)).background(t.surface1)
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(Radius.thumb))
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Ic(I.Clean, size = 20.dp, tint = t.text2)
        Text("정리할 사진·영상 ${count}개", style = fonts.metaStrong, color = t.text1, maxLines = 2)
    }
}

/** 월 스크러버: 지금 보이는 달 + 오른쪽 가장자리 손잡이. 누르면 월별 이동 시트가 열린다. */
@Composable
private fun MonthScrubber(label: String, modifier: Modifier, onClick: () -> Unit) {
    val t = theme
    Row(
        modifier.padding(top = 116.dp, end = 2.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(t.surface3).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = fonts.metaStrong, color = t.text1, maxLines = 1) }
        Box(Modifier.width(5.dp).height(44.dp).clip(RoundedCornerShape(3.dp)).background(t.text1))
    }
}

/** 분석 진행 알약: 작은 링 + "영상 3/13개 · 장면 분석 2/8". 갤러리를 가리지 않게 탭 바 바로 위에 뜬다. */
@Composable
private fun ProgressPill(text: String, fraction: Float, modifier: Modifier) {
    val t = theme
    Row(
        modifier.padding(bottom = 12.dp).height(36.dp)
            .clip(RoundedCornerShape(18.dp)).background(t.surface2)
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp))
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val track = t.track
        val head = t.accent
        Box(
            Modifier.size(18.dp).drawBehind {
                val s = 2.25.dp.toPx()
                val arc = Size(size.width - s, size.height - s)
                drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s))
                drawArc(head, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), arc, style = Stroke(s))
            },
        )
        Text(text, style = fonts.meta.copy(fontWeight = FontWeight.Medium), color = t.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
