package com.moalog.personal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.moalog.personal.data.MonthRow
import com.moalog.personal.data.PhotoCard

/**
 * 썸네일 한 장. Coil이 content URI를 직접 읽고 메모리·디스크 캐시를 관리한다.
 *
 * `size`를 명시하는 이유: 원본 해상도로 디코드하면 1만 장 그리드에서 메모리가 터진다.
 * MediaStore가 만들어 둔 썸네일을 쓰는 것과 같은 취지다(§6.1).
 */
@Composable
fun Thumb(uri: String?, modifier: Modifier = Modifier, px: Int = 320) {
    val t = theme
    Box(modifier.background(t.surface2)) {
        if (uri != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(uri).size(px).crossfade(140).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

// ---------------------------------------------------------------- 공통 그리드

/**
 * 날짜별로 묶인 사진 격자. 사진 탭과 앨범 상세가 같은 것을 쓴다 —
 * 조작이 화면마다 다르면 사용자가 매번 다시 배워야 한다.
 *
 * **길게 누르면 선택 모드**로 들어간다. 일반 갤러리의 몸에 밴 조작이고,
 * 선택 버튼을 찾아 누르는 것보다 한 단계 짧다.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.dayPhotoItems(
    days: List<AppViewModel.DayGroup>,
    selected: Set<Long>,
    selecting: Boolean,
    favorites: Set<Long>,
    columns: Int,
    onOpen: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    for (g in days) {
        item(key = "h:${g.day}:${g.photos.firstOrNull()?.id}") {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(g.day, style = fonts.body.copy(fontWeight = FontWeight.Bold), color = theme.text)
                Text(g.note, style = fonts.meta, color = theme.text.copy(alpha = .38f))
            }
        }
        items(g.photos.chunked(columns), key = { row -> "r:${row.first().id}" }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (p in row) Box(
                    Modifier.weight(1f).aspectRatio(1f).combinedClickable(
                        onClick = { if (selecting) onToggle(p.id) else onOpen(p.id) },
                        onLongClick = { onLongPress(p.id) },
                    )
                ) {
                    Thumb(p.uri, Modifier.matchParentSize())
                    PhotoOverlay(p.id in selected, selecting, p.id in favorites)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 선택 표시와 즐겨찾기 표시. 둘 다 썸네일 위에 얹는다. */
@Composable
private fun BoxScope.PhotoOverlay(selected: Boolean, selecting: Boolean, favorite: Boolean) {
    val t = theme
    if (selected) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)))
    if (favorite) Text(
        "♥", style = fonts.badge, color = Color.White.copy(alpha = .92f),
        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
    )
    if (selecting) Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
            .background(if (selected) t.accent else Color.Black.copy(alpha = .28f)),
        contentAlignment = Alignment.Center,
    ) { if (selected) Text("✓", style = fonts.badge, color = Color.White) }
}

/** 목록 끝이 보이면 다음 페이지를 부른다. 스크롤이 멈춘 뒤 부르면 이미 늦다. */
@Composable
private fun LoadMoreWhenNearEnd(state: LazyListState, threshold: Int = 8, onLoad: () -> Unit) {
    val near by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(state) { snapshotFlow { near }.collect { if (it) onLoad() } }
}

// ---------------------------------------------------------------- 사진 탭

/**
 * 전체 사진. **분류를 몰라도 사진에 닿을 수 있어야 한다** — 예전 홈은 앨범 목록만
 * 보여 줘서, 분류가 안 끝났거나 분류를 못 믿는 사용자는 갈 곳이 없었다.
 *
 * 위쪽에 추억 모음을 작게 두고, 그 아래는 날짜순 전체 사진이다.
 */
@Composable
fun PhotosScreen(vm: AppViewModel, ui: AppViewModel.Ui, columns: Int = 3) {
    val t = theme
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ids = remember(ui.photoDays) { ui.photoDays.flatMap { it.photos }.map(PhotoCard::id) }

    Column(Modifier.fillMaxSize()) {
        if (ui.selectionMode) SelectionHeader(vm, ui)
        else Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("사진", style = fonts.screenTitle, color = t.text)
                Text(subtitle(ui), style = fonts.meta, color = t.text.copy(alpha = .45f))
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(t.surface5)
                    .clickable { vm.go(Screen.Settings) },
                contentAlignment = Alignment.Center,
            ) { Text("설", style = fonts.chip.copy(fontWeight = FontWeight.Bold), color = t.text.copy(alpha = .7f)) }
        }

        LazyColumn(Modifier.weight(1f), state = state) {
            if (!ui.selectionMode) {
                item {
                    Row(
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp).fillMaxWidth()
                            .height(46.dp).clip(RoundedCornerShape(Radius.field)).background(t.surface2)
                            .clickable { vm.go(Screen.Search) }.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(14.dp).border(1.6.dp, t.text.copy(alpha = .42f), CircleShape))
                        Text("\"작년 여름 바다\" 처럼 말로 찾기", style = fonts.body, color = t.text.copy(alpha = .42f))
                    }
                }
                if (ui.memories.isNotEmpty()) item {
                    MemoryStrip(ui.memories) { m -> vm.openViewer(m.photos.map(PhotoCard::id), m.photos.first().id) }
                }
                // 정리 제안. 홈에서 앨범을 길게 늘어놓는 대신 지금 할 만한 일 하나만 둔다.
                if (ui.cleanupCount > 0) item {
                    Column(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card)).background(t.accentTint(.06f))
                            .clickable { vm.go(Screen.Cleanup) }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("정리할 사진 ${ui.cleanupCount}장", style = fonts.itemTitle, color = t.accentHi)
                        Text(
                            "겹치는 사진을 크게 비교하고 보관할 한 장을 직접 고를 수 있습니다.",
                            style = fonts.meta, color = t.textMuted,
                        )
                    }
                }
                if (ui.months.size > 1) item {
                    MonthStrip(ui.months) { m ->
                        vm.jumpToMonth(m)
                        scope.launch { state.scrollToItem(0) }
                    }
                }
            }

            dayPhotoItems(
                days = ui.photoDays,
                selected = ui.selected, selecting = ui.selectionMode, favorites = ui.favorites,
                columns = columns,
                onOpen = { vm.openViewer(ids, it) },
                onToggle = vm::toggleSelect,
                onLongPress = { if (ui.selectionMode) vm.toggleSelect(it) else vm.startSelection(it) },
            )

            if (ui.photoDays.isEmpty()) item { EmptyNote(ui) }
            item { Spacer(Modifier.height(20.dp)) }
        }
        LoadMoreWhenNearEnd(state) { vm.loadPhotos() }

        if (ui.selectionMode) SelectionBar(vm, ui)
    }
}

private fun subtitle(ui: AppViewModel.Ui): String = when {
    ui.indexing -> ui.workMessage.ifBlank { "분석 중" }
    ui.counts.total == 0 -> "사진을 찾지 못했습니다"
    ui.counts.done < ui.counts.total -> "${ui.counts.total}장 · AI 분류 ${ui.counts.done}장 완료"
    else -> "${ui.counts.total}장"
}

/** 추억 모음. 사진이 모자라면 뷰모델이 아예 만들지 않으므로 여기서 빈 칸을 그릴 일이 없다. */
@Composable
private fun MemoryStrip(memories: List<AppViewModel.Memory>, onOpen: (AppViewModel.Memory) -> Unit) {
    val t = theme
    Column(Modifier.padding(bottom = 6.dp)) {
        SectionLabel("모아보기", Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            items(memories, key = { it.key }) { m ->
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(Radius.card))
                        .background(t.surface).clickable { onOpen(m) },
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Thumb(m.photos.getOrNull(0)?.uri, Modifier.weight(2f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Thumb(m.photos.getOrNull(1)?.uri, Modifier.weight(1f).fillMaxWidth())
                            Thumb(m.photos.getOrNull(2)?.uri, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(m.title, style = fonts.itemTitle, color = t.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(m.note, style = fonts.meta, color = t.textMuted)
                    }
                }
            }
        }
    }
}

/**
 * 월 이동. **사진을 다 불러오지 않고도 뛴다** — 각 달의 시작 위치를 개수만 세어
 * 알아 두었으므로(Store.months), 누르면 그 자리부터 다시 읽는다.
 */
@Composable
private fun MonthStrip(months: List<MonthRow>, onJump: (MonthRow) -> Unit) {
    Column {
        SectionLabel("월별 이동", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            items(months, key = { it.ym }) { m -> Chip(monthLabel(m.ym)) { onJump(m) } }
        }
    }
}

private fun monthLabel(ym: String): String {
    val parts = ym.split("-")
    if (parts.size != 2) return ym
    return "${parts[0]}년 ${parts[1].trimStart('0')}월"
}

@Composable
private fun EmptyNote(ui: AppViewModel.Ui) {
    val t = theme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (ui.indexing) "사진을 읽고 있습니다" else "아직 보여 줄 사진이 없습니다",
            style = fonts.itemTitle, color = t.textBody,
        )
        Text(
            if (ui.indexing) "${ui.counts.done} / ${ui.counts.total}장"
            else "설정에서 '전체 다시 분석'을 눌러 시작할 수 있습니다.",
            style = fonts.caption, color = t.textFaint,
        )
    }
}

// ---------------------------------------------------------------- 선택 모드

@Composable
private fun SelectionHeader(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val all = ui.visibleIds
    val allSelected = all.isNotEmpty() && ui.selected.containsAll(all)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(t.surface3)
                .clickable { vm.cancelSelection() },
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = fonts.chip, color = t.text.copy(alpha = .8f)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("${ui.selected.size}장 선택", style = fonts.header, color = t.text)
            // 화면에 안 깔린 사진은 '전체 선택'에 들어가지 않는다. 그 사실을 숨기지 않는다.
            Text("화면에 불러온 ${all.size}장 중에서", style = fonts.meta, color = t.text.copy(alpha = .42f))
        }
        Box(
            Modifier.clip(RoundedCornerShape(15.dp)).background(t.surface3)
                .clickable { vm.toggleSelectAll() }.padding(horizontal = 11.dp, vertical = 8.dp),
        ) { Text(if (allSelected) "선택 해제" else "전체 선택", style = fonts.meta, color = t.text) }
    }
}

/**
 * 선택 후 할 수 있는 일. **`앨범에서 빼기`와 `휴지통으로`를 나란히 두되 색을 다르게 한다** —
 * 묶음에서 빼는 것과 기기에서 지우는 것은 사용자에게 완전히 다른 행동이다.
 */
@Composable
private fun SelectionBar(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val actions = LocalActions.current
    val n = ui.selected.size
    val album = ui.openAlbum
    val removable = ui.screen == Screen.Category && album?.removable == true
    Column(
        Modifier.fillMaxWidth().background(t.bg).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            item { BarButton("즐겨찾기", enabled = n > 0) { vm.favoriteSelected() } }
            item { BarButton("공유", enabled = n > 0) { actions.share(ui.selected) } }
            item { BarButton("앨범에 추가", enabled = n > 0) { vm.openPicker() } }
            if (removable) item {
                BarButton("앨범에서 빼기", enabled = n > 0) { vm.removeSelectedFromAlbum() }
            }
        }
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(if (n > 0) t.accentHi else t.surface4)
                .then(if (n > 0) Modifier.clickable { actions.trash(ui.selected) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (n > 0) "${n}장 휴지통으로" else "사진을 선택하세요",
                style = fonts.itemTitle, color = if (n > 0) Color.White else t.textFaint,
            )
        }
    }
}

@Composable
private fun BarButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = theme
    Box(
        Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(t.surface3)
            .padding(horizontal = 16.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = fonts.chip, color = if (enabled) t.text.copy(alpha = .85f) else t.textFaint)
    }
}

// ---------------------------------------------------------------- 앨범 탭

/**
 * 앨범. **출처별로 나눈다** — 내 앨범 / 기기 폴더 / AI 분류 / 기록.
 * 예전에는 넷이 한 줄에 섞여 있어서 "카메라"와 "음식"이 같은 종류로 보였다.
 */
@Composable
fun AlbumsScreen(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    val t = theme
    val byKind = remember(ui.albums, ui.kindFilter, ui.axis) {
        AlbumKind.ordered.associateWith { kind ->
            ui.albums.filter {
                it.kind == kind &&
                    (ui.kindFilter == null || ui.kindFilter == kind) &&
                    (kind != AlbumKind.Tag || ui.axis == Axis.All || it.axis == ui.axis)
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Text("앨범", style = fonts.screenTitle, color = t.text,
                    modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(bottom = 6.dp)) {
                    item { Chip("전체", selected = ui.kindFilter == null) { vm.setKindFilter(null) } }
                    items(AlbumKind.ordered) { k ->
                        Chip(k.chip, selected = ui.kindFilter == k) { vm.setKindFilter(k) }
                    }
                }
            }
        }

        for (kind in AlbumKind.ordered) {
            val list = byKind[kind].orEmpty()
            if (list.isEmpty()) continue
            item(span = { GridItemSpan(2) }) {
                Column {
                    SectionLabel(kind.label, Modifier.padding(top = 10.dp, bottom = 2.dp))
                    Text(kindNote(kind), style = fonts.meta, color = t.text.copy(alpha = .38f))
                    // AI 앨범만 축 필터를 쓴다. 폴더는 축이 없다.
                    if (kind == AlbumKind.Tag) LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) { items(Axis.ordered) { a -> Chip(a.label, selected = a == ui.axis) { vm.setAxis(a) } } }
                }
            }
            items(list, key = { it.key }) { a -> AlbumCard(a, uriOf) { vm.openAlbum(a) } }
        }

        if (byKind.values.all { it.isEmpty() }) item(span = { GridItemSpan(2) }) { EmptyNote(ui) }
    }
}

private fun kindNote(k: AlbumKind): String = when (k) {
    AlbumKind.User -> "직접 만든 모음. 사진은 원래 자리에 그대로 있습니다."
    AlbumKind.Folder -> "사진이 실제로 저장된 위치입니다."
    AlbumKind.Tag -> "내용으로 묶은 분류. 한 사진이 여러 앨범에 들어갈 수 있습니다."
    AlbumKind.Event -> "촬영 시각과 위치로 묶은 기록입니다."
}

@Composable
private fun AlbumCard(a: AppViewModel.Album, uriOf: (Long) -> String?, onClick: () -> Unit) {
    val t = theme
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(Radius.card))) {
            Thumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.matchParentSize())
            Box(
                Modifier.align(Alignment.BottomStart).padding(10.dp)
                    .clip(RoundedCornerShape(6.dp)).background(t.bg.copy(alpha = .72f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (a.kind == AlbumKind.Tag) a.axis.label else a.kind.chip, style = fonts.badge, color = t.text) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(a.title, style = fonts.body.copy(fontWeight = FontWeight.Bold), color = t.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${a.count}장", style = fonts.meta, color = t.text.copy(alpha = .42f))
        }
    }
}

// ---------------------------------------------------------------- 앨범 상세

@Composable
fun CategoryScreen(vm: AppViewModel, ui: AppViewModel.Ui, columns: Int = 3) {
    val t = theme
    val album = ui.openAlbum ?: return
    val state = rememberLazyListState()
    val ids = remember(ui.albumDays) { ui.albumDays.flatMap { it.photos }.map(PhotoCard::id) }

    Column(Modifier.fillMaxSize()) {
        if (ui.selectionMode) SelectionHeader(vm, ui)
        else Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton { vm.back() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(album.title, style = fonts.header, color = t.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${album.kind.label} · ${album.count}장", style = fonts.meta,
                    color = t.text.copy(alpha = .42f))
            }
            if (album.kind == AlbumKind.Tag) HeaderButton("분류 수정") { vm.openFix() }
            if (album.kind == AlbumKind.User && !album.isFavorites)
                HeaderButton("앨범 삭제") { vm.deleteOpenAlbum() }
            HeaderButton("선택") { vm.startSelection() }
        }

        LazyColumn(Modifier.weight(1f), state = state) {
            dayPhotoItems(
                days = ui.albumDays,
                selected = ui.selected, selecting = ui.selectionMode, favorites = ui.favorites,
                columns = columns,
                onOpen = { vm.openViewer(ids, it) },
                onToggle = vm::toggleSelect,
                onLongPress = { if (ui.selectionMode) vm.toggleSelect(it) else vm.startSelection(it) },
            )
            if (ui.albumDays.isEmpty()) item {
                Text(if (ui.loadingMore) "불러오는 중…" else "이 앨범에는 사진이 없습니다.",
                    style = fonts.caption, color = theme.textFaint, modifier = Modifier.padding(34.dp))
            }
            // 601번째 사진이 존재하지 않는 것처럼 굴지 않는다. 몇 장 중 몇 장인지 적는다.
            if (ui.albumLoaded in 1 until album.count) item {
                Text("${ui.albumLoaded} / ${album.count}장 불러옴", style = fonts.meta,
                    color = theme.textFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp))
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        LoadMoreWhenNearEnd(state) { vm.loadAlbumPage() }

        if (ui.selectionMode) SelectionBar(vm, ui)
    }
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) {
    val t = theme
    Box(
        Modifier.clip(RoundedCornerShape(15.dp)).background(t.surface3)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(text, style = fonts.meta.copy(fontWeight = FontWeight.Bold), color = t.text.copy(alpha = .75f)) }
}
