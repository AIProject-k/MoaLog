package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moalog.personal.data.PhotoCard

/**
 * 앨범 탭. **출처별로 나눈다** — 내 앨범 / 기기 폴더 / AI 앨범 / 기록. 예전에는 넷이 한 줄에 섞여 있어서
 * "카메라"와 "음식"이 같은 종류로 보였다.
 *
 * 위계 3단(디자인 결정 5·6): ① 미디어 세그먼트(전체 폭, 항상 보임) → ② 종류 텍스트 탭(밑줄) →
 * ③ AI 축은 AI 섹션 머리의 드롭다운. `동영상`을 고르면 목록 전체가 **영상만으로 다시 분류**되고 세그먼트가
 * 강조색으로 채워져 지금 보기가 멀리서도 읽힌다.
 */
@Composable
fun AlbumsScreen(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    val media = ui.albumMedia
    val byKind = remember(ui.albums, ui.axis) {
        AlbumKind.ordered.associateWith { kind ->
            ui.albums.filter { it.kind == kind && (kind != AlbumKind.Tag || ui.axis == Axis.All || it.axis == ui.axis) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(height = 56.dp) {
            Text("앨범", style = fonts.title, color = theme.text1, modifier = Modifier.weight(1f).padding(start = 16.dp))
            Row(
                Modifier.height(48.dp).tap({ vm.openSheet(Sheet.Add) }, "새 앨범").padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Ic(I.Plus, size = 20.dp, tint = theme.text2)
                Text("새 앨범", style = fonts.chip, color = theme.text2)
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            MediaSegment(media, vm::setAlbumMedia, fill = true)
        }
        KindTabs(ui.kindFilter, vm::setKindFilter)

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (media == "video") item(key = "banner") { VideoBanner(ui.videoTotal) }
            for (kind in AlbumKind.ordered) {
                if (ui.kindFilter != null && ui.kindFilter != kind) continue
                val list = byKind[kind].orEmpty()
                item(key = "sec:${kind.name}") { AlbumSection(kind, list, ui, media, uriOf, vm) }
            }
            item(key = "end") { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/** 종류 텍스트 탭: 선택은 글자가 밝아지고 밑줄이 붙는다. 점프가 아니라 **필터**다. */
@Composable
private fun KindTabs(selected: AlbumKind?, onSelect: (AlbumKind?) -> Unit) {
    val t = theme
    val tabs = listOf<Pair<AlbumKind?, String>>(null to "전체") + AlbumKind.ordered.map { it to it.label }
    Row(
        Modifier.fillMaxWidth().height(48.dp).drawBehind {
            drawLine(t.hair, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        for ((kind, label) in tabs) {
            val on = kind == selected
            Box(
                Modifier.height(48.dp)
                    .semantics { role = Role.Tab; contentDescription = label }
                    .clickable { onSelect(kind) }
                    .drawBehind {
                        if (on) drawLine(t.text1, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = fonts.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) t.text1 else t.text3, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VideoBanner(videoTotal: Int) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(Radius.thumb)).background(theme.surface1).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Ic(I.Video, size = 18.dp, tint = theme.accent)
        Text(
            "영상 ${"%,d".format(videoTotal)}개를 기준으로 다시 묶었습니다. 영상이 없는 앨범은 숨겨집니다.",
            style = fonts.note.copy(lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = theme.text2, modifier = Modifier.weight(1f),
        )
    }
}

private fun kindNote(k: AlbumKind): String = when (k) {
    AlbumKind.User -> "직접 만든 모음. 사진·영상은 원래 자리에 그대로 있습니다."
    AlbumKind.Folder -> "사진·영상이 실제로 저장된 위치입니다."
    AlbumKind.Tag -> "내용으로 묶은 분류. 하나가 여러 앨범에 들어갈 수 있습니다."
    AlbumKind.Event -> "촬영 시각과 위치로 묶은 기록입니다."
    AlbumKind.Memory -> ""
}

private fun kindIcon(k: AlbumKind): Int = when (k) {
    AlbumKind.User -> I.Mine
    AlbumKind.Folder -> I.Folder
    AlbumKind.Tag -> I.Ai
    AlbumKind.Event -> I.Record
    AlbumKind.Memory -> I.Gallery
}

/** 섹션 하나: 머리(아이콘·제목·개수·설명[·분류 드롭다운]) + 비었으면 안내 카드, 아니면 종류별 배치. */
@Composable
private fun AlbumSection(
    kind: AlbumKind,
    list: List<AppViewModel.Album>,
    ui: AppViewModel.Ui,
    media: String,
    uriOf: (Long) -> String?,
    vm: AppViewModel,
) {
    val t = theme
    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ic(kindIcon(kind), size = 18.dp, tint = t.text2)
                    Text(kind.label, style = fonts.item, color = t.text1)
                    Text("${list.size}", style = fonts.meta, color = t.text3)
                }
                Prose(kindNote(kind), style = fonts.meta.copy(lineHeight = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text3)
            }
            if (kind == AlbumKind.Tag) Row(
                Modifier.padding(end = 12.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.surface2)
                    .tap({ vm.openSheet(Sheet.Axis) }, "AI 앨범 분류")
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("분류: ${ui.axis.label}", style = fonts.chip, color = t.text2, maxLines = 1)
                Ic(I.ChevronDown, size = 18.dp, tint = t.text2)
            }
        }

        if (list.isEmpty()) {
            val (title, desc) = emptyNote(kind, media, ui.axis)
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = fonts.label, color = t.text1, textAlign = TextAlign.Center)
                if (desc != null) Prose(desc, style = fonts.note, color = t.text2, textAlign = TextAlign.Center)
            }
        } else when (kind) {
            AlbumKind.User, AlbumKind.Folder -> LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) { items(list, key = { it.key }) { a -> RowCard(a, media, uriOf) { vm.openAlbum(a) } } }

            AlbumKind.Tag -> Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (pair in list.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (a in pair) Box(Modifier.weight(1f)) { GridCard(a, media, uriOf) { vm.openAlbum(a) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            AlbumKind.Event, AlbumKind.Memory -> Column(Modifier.padding(top = 8.dp)) {
                for (a in list) ListRow(a, media, uriOf) { vm.openAlbum(a) }
            }
        }
    }
}

private fun emptyNote(kind: AlbumKind, media: String, axis: Axis): Pair<String, String?> = when {
    kind == AlbumKind.Tag && media == "video" ->
        "분류된 동영상이 없습니다" to "영상 분석이 끝나면 장면별로 나뉘어 이곳에 생깁니다."
    kind == AlbumKind.Tag && axis != Axis.All -> "이 분류에 해당하는 앨범이 없습니다." to null
    media == "video" -> "동영상이 든 앨범이 없습니다." to null
    else -> "앨범이 없습니다." to null
}

/**
 * 앨범 카드·머리글의 개수 문구. 종류를 갈랐으면 그 종류만 말하고, 전체에서는 영상이 섞여 있을 때
 * 몇 개인지 덧붙인다 — 영상이 어느 앨범에 들어 있는지 앨범을 열어 보기 전에 알 수 있어야 한다.
 */
fun albumCountText(a: AppViewModel.Album, media: String): String = when (media) {
    "video" -> "영상 ${a.count}개"
    "image" -> "사진 ${a.count}장"
    else -> when {
        a.videos == 0 -> "${a.count}개"
        a.videos == a.count -> "영상 ${a.count}개"
        else -> "${a.count}개 · 영상 ${a.videos}"
    }
}

private fun coverIsVideo(a: AppViewModel.Album) = (a.coverIds.firstOrNull() ?: 0L) < 0

/** 영상 표지에 붙는 작은 ▶ 원. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlayDot(size: Int, icon: Int, inset: Int) {
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = inset.dp, bottom = inset.dp).size(size.dp)
            .background(Color(0x99000000), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Ic(I.Play, size = icon.dp, tint = Color.White) }
}

/** 가로로 넘기는 앨범 카드(내 앨범·기기 폴더). 112dp 정사각 표지. */
@Composable
private fun RowCard(a: AppViewModel.Album, media: String, uriOf: (Long) -> String?, onClick: () -> Unit) {
    val t = theme
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(Radius.thumb)).clickable(onClick = onClick)) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(Radius.thumb))) {
            PlainThumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.fillMaxSize())
            if (a.isFavorites) Box(Modifier.fillMaxSize().background(Color(0x40000000)), contentAlignment = Alignment.Center) {
                Ic(I.HeartFilled, size = 32.dp, tint = Color.White)
            }
            if (coverIsVideo(a)) PlayDot(22, 12, 6)
        }
        Text(a.title, style = fonts.bodySmStrong.copy(lineHeight = androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = t.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(albumCountText(a, media), style = fonts.meta, color = t.text3, maxLines = 1)
    }
}

/** 2열 격자 카드(AI 앨범). 정사각 표지. */
@Composable
private fun GridCard(a: AppViewModel.Album, media: String, uriOf: (Long) -> String?, onClick: () -> Unit) {
    val t = theme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radius.thumb))) {
            PlainThumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.fillMaxSize())
            if (coverIsVideo(a)) PlayDot(26, 14, 8)
        }
        Text(a.title, style = fonts.label, color = t.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(albumCountText(a, media), style = fonts.meta, color = t.text3)
    }
}

/** 목록 행(기록). 52dp 작은 썸네일 + 제목·메타 + 화살표. */
@Composable
private fun ListRow(a: AppViewModel.Album, media: String, uriOf: (Long) -> String?, onClick: () -> Unit) {
    val t = theme
    Row(
        Modifier.fillMaxWidth().height(68.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlainThumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.size(52.dp).clip(RoundedCornerShape(Radius.tile)))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = fonts.label, color = t.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(albumCountText(a, media), style = fonts.meta, color = t.text3)
        }
        Ic(I.ChevronRight, size = 20.dp, tint = t.text3)
    }
}

// ---------------------------------------------------------------- 앨범 상세

/**
 * 앨범 하나를 연 화면. 머리글은 제목 + "종류 · 개수" + 선택(✓) + (가능한 동작이 있을 때만) 더보기(⋮).
 * 종류마다 가능한 동작이 다르다 — AI: 분류 수정 / 내 앨범: 앨범 삭제 / 즐겨찾기·폴더·기록: 없음.
 * 없는 동작은 아예 보이지 않는다(디자인 결정: 헤더 칩 3개 → 아이콘 + 더보기).
 */
@Composable
fun CategoryScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val album = ui.openAlbum ?: return
    val actions = LocalActions.current
    val state = rememberLazyListState()
    val rows = remember(ui.albumDays) { buildRows(ui.albumDays) }
    val ids = remember(ui.albumDays) { ui.albumDays.flatMap { it.photos }.map(PhotoCard::id) }
    val selecting = ui.selectionMode
    val media = ui.albumMedia
    val hasMenu = album.kind == AlbumKind.Tag || (album.kind == AlbumKind.User && !album.isFavorites)

    Column(Modifier.fillMaxSize()) {
        if (selecting) SelectionTopBar(
            title = if (ui.selected.isEmpty()) "사진·영상을 선택하세요" else "${ui.selected.size}개 선택",
            sub = selectionSub(ui.selected, ids.size),
            allSelected = ui.visibleIds.isNotEmpty() && ui.selected.containsAll(ui.visibleIds),
            onCancel = vm::cancelSelection, onSelectAll = vm::toggleSelectAll,
        ) else TopRow(height = 64.dp) {
            IconBtn(I.Back, "뒤로", vm::back)
            TitleBlock(
                album.title, "${album.kind.label} · ${albumCountText(album, media)}",
                Modifier.weight(1f).padding(start = 4.dp), titleStyle = fonts.header,
                subIcon = if (album.isFavorites) I.Heart else kindIcon(album.kind),
            )
            IconBtn(I.Check, "선택", { vm.startSelection() })
            if (hasMenu) IconBtn(I.More, "더보기", { vm.setMenu(true) })
            else Spacer(Modifier.width(4.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            item(key = "segment") {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaSegment(media, vm::setAlbumMedia)
                }
            }
            gridRows(
                rows = rows, selecting = selecting, selected = ui.selected, favorites = ui.favorites,
                onOpen = { vm.openViewer(ids, it) },
                onToggle = vm::toggleSelect,
                onLongPress = { if (selecting) vm.toggleSelect(it) else vm.startSelection(it) },
            )
            if (ui.albumDays.isEmpty()) item(key = "empty") {
                when {
                    ui.loadingMore -> EmptyState(I.Album, "불러오는 중…")
                    media == "video" -> EmptyState(I.Video, "이 앨범에는 동영상이 없습니다.")
                    media == "image" -> EmptyState(I.Photo, "이 앨범에는 사진이 없습니다.")
                    else -> EmptyState(I.Album, "이 앨범은 비어 있습니다.", description = "갤러리에서 길게 눌러 선택한 뒤 앨범에 추가하세요.")
                }
            }
            // 601번째 사진이 존재하지 않는 것처럼 굴지 않는다. 몇 장 중 몇 장인지 적는다.
            if (ui.albumLoaded > 0) item(key = "footer") {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    Text("${ui.albumLoaded} / ${album.count}개 불러옴", style = fonts.meta, color = theme.text3)
                }
            }
            item(key = "end") { Spacer(Modifier.height(120.dp)) }
        }
        LoadMoreWhenNearEnd(state) { vm.loadAlbumPage() }

        if (selecting) {
            val n = ui.selected.size
            SelectionBottomBar(
                actions = selectionActions(vm, ui, album),
                trashLabel = if (n > 0) "${n}개 휴지통으로" else "휴지통으로",
                trashEnabled = n > 0,
                onTrash = { actions.trash(ui.selected) },
            )
        }
    }
}
