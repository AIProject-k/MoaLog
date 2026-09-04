package kr.slz.photolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kr.slz.photolog.data.PhotoCard

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

@Composable
private fun ThumbById(id: Long?, uriOf: (Long) -> String?, modifier: Modifier) =
    Thumb(id?.let(uriOf), modifier)

// ---------------------------------------------------------------- HOME

/**
 * 홈. 디자인의 순서를 지킨다: 헤더 → 검색바 → 오늘의 이야기 → 자동 분류 앨범 → 정리 제안.
 *
 * 비어 있을 때 가짜 카드를 그리지 않는다 — 인덱싱이 안 끝났으면 그 사실을 말한다.
 */
@Composable
fun HomeScreen(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    val t = theme
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("사진 정리함", style = fonts.screenTitle, color = t.text)
                    Text(homeSubtitle(ui), style = fonts.meta, color = t.text.copy(alpha = .45f))
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(t.surface5)
                        .clickable { vm.go(Screen.Settings) },
                    contentAlignment = Alignment.Center,
                ) { Text("설", style = fonts.chip.copy(fontWeight = FontWeight.Bold), color = t.text.copy(alpha = .7f)) }
            }
        }

        item {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp).fillMaxWidth()
                    .height(46.dp).clip(RoundedCornerShape(Radius.field)).background(t.surface2)
                    .clickable { vm.go(Screen.Search) }.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(14.dp).border(1.6.dp, t.text.copy(alpha = .42f), CircleShape))
                Text("\"작년 여름 바다\" 처럼 말로 찾기", style = fonts.body, color = t.text.copy(alpha = .42f))
            }
        }

        // 오늘의 이야기 — 가장 최근 이벤트. 없으면 섹션 자체를 뺀다.
        ui.events.firstOrNull()?.let { hero ->
            item { SectionLabel("오늘의 이야기", Modifier.padding(start = 20.dp, bottom = 10.dp)) }
            item {
                Column(
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.cardLg)).background(t.surface2)
                        .clickable {
                            ui.albums.firstOrNull { it.key == "event:${hero.id}" }?.let(vm::openAlbum)
                        },
                ) {
                    Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Thumb(hero.coverIds.getOrNull(0)?.let(uriOf), Modifier.weight(2f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Thumb(hero.coverIds.getOrNull(1)?.let(uriOf), Modifier.weight(1f).fillMaxWidth())
                            Thumb(hero.coverIds.getOrNull(2)?.let(uriOf), Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    Column(
                        Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(t.accentTint(.16f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("자동 생성", style = fonts.badge, color = t.accent) }
                            Text(hero.placeName ?: "${hero.count}장", style = fonts.meta,
                                color = t.text.copy(alpha = .4f))
                        }
                        Text(hero.title, style = fonts.hero, color = t.text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "촬영 시각과 위치로 묶은 기록입니다. ${hero.count}장.",
                            style = fonts.caption, color = t.textMuted,
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                SectionLabel("자동 분류된 앨범", Modifier.weight(1f))
                Text("전체보기", style = fonts.meta, color = t.accent,
                    modifier = Modifier.clickable { vm.go(Screen.Albums) })
            }
        }

        val tagAlbums = ui.albums.filter { it.kind == AppViewModel.Album.Kind.Tag }.take(6)
        if (tagAlbums.isEmpty()) {
            item { EmptyNote(ui) }
        } else items(tagAlbums, key = { it.key }) { a ->
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card)).background(t.surface)
                    .clickable { vm.openAlbum(a) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Thumb(a.coverIds.firstOrNull()?.let(uriOf),
                    Modifier.size(62.dp).clip(RoundedCornerShape(Radius.thumb)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(a.title, style = fonts.itemTitle, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.meta, style = fonts.meta, color = t.text.copy(alpha = .45f))
                }
                Text("${a.count}장", style = fonts.meta, color = t.text.copy(alpha = .3f))
            }
        }

        if (ui.cleanupCount > 0) item {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card)).background(t.accentTint(.06f))
                    .clickable { vm.go(Screen.Settings) }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("중복 사진 ${ui.cleanupCount}장 정리 제안", style = fonts.itemTitle, color = t.accentHi)
                Text(
                    "비슷한 사진 중 가장 잘 나온 한 장만 남깁니다. 파일은 시스템 휴지통으로만 갑니다.",
                    style = fonts.meta, color = t.textMuted,
                )
            }
        }
    }
}

private fun homeSubtitle(ui: AppViewModel.Ui): String = when {
    ui.indexing -> ui.workMessage.ifBlank { "분석 중" }
    ui.counts.total == 0 -> "사진을 찾지 못했습니다"
    ui.counts.done < ui.counts.total -> "${ui.counts.total}장 · AI 태그 ${ui.counts.done}장 완료"
    else -> "${ui.counts.total}장 · 분석 완료"
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
            if (ui.indexing) "장면을 분류하고 있습니다" else "아직 분류된 앨범이 없습니다",
            style = fonts.itemTitle, color = t.textBody,
        )
        Text(
            if (ui.indexing) "${ui.counts.done} / ${ui.counts.total}장"
            else "설정에서 '전체 다시 분석'을 눌러 시작할 수 있습니다.",
            style = fonts.caption, color = t.textFaint,
        )
    }
}

// ---------------------------------------------------------------- ALBUMS

@Composable
fun AlbumsScreen(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    val t = theme
    val shown = ui.albums.filter { ui.axis == Axis.All || it.axis == ui.axis }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Column {
                Text("앨범", style = fonts.screenTitle, color = t.text,
                    modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(bottom = 14.dp)) {
                    items(Axis.ordered) { a ->
                        Chip(a.label, selected = a == ui.axis) { vm.setAxis(a) }
                    }
                }
            }
        }
        items(shown, key = { it.key }) { a ->
            Column(
                Modifier.clickable { vm.openAlbum(a) },
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(Radius.card))) {
                    Thumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.matchParentSize())
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .clip(RoundedCornerShape(6.dp)).background(t.bg.copy(alpha = .72f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text(a.axis.label, style = fonts.badge, color = t.text) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(a.title, style = fonts.body.copy(fontWeight = FontWeight.Bold), color = t.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.count}장", style = fonts.meta, color = t.text.copy(alpha = .42f))
                }
            }
        }
        if (shown.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            EmptyNote(ui)
        }
    }
}

// ---------------------------------------------------------------- CATEGORY

@Composable
fun CategoryScreen(
    vm: AppViewModel,
    ui: AppViewModel.Ui,
    uriOf: (Long) -> String?,
    onTrash: (Set<Long>) -> Unit,
    columns: Int = 3,
) {
    val t = theme
    val album = ui.openAlbum ?: return
    val selecting = ui.albumSelectionMode
    val selected = ui.albumSelected
    val allIds = ui.albumDays.flatMap { it.photos }.map { it.id }.toSet()
    val allSelected = allIds.isNotEmpty() && selected.containsAll(allIds)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton { if (selecting) vm.cancelAlbumSelection() else vm.back() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    if (selecting) "${selected.size}장 선택" else album.title,
                    style = fonts.header, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (selecting) "사진을 눌러 선택하거나 해제하세요" else "${album.meta} · ${album.count}장",
                    style = fonts.meta, color = t.text.copy(alpha = .42f),
                )
            }
            if (selecting) {
                Box(
                    Modifier.clip(RoundedCornerShape(15.dp)).background(t.surface3)
                        .clickable { vm.toggleAlbumSelectAll() }.padding(horizontal = 11.dp, vertical = 8.dp),
                ) { Text(if (allSelected) "선택 해제" else "전체 선택", style = fonts.meta, color = t.text) }
            } else {
                Box(
                    Modifier.clip(RoundedCornerShape(15.dp)).background(t.surface3)
                        .clickable { vm.startAlbumSelection() }.padding(horizontal = 13.dp, vertical = 8.dp),
                ) { Text("선택", style = fonts.meta.copy(fontWeight = FontWeight.Bold), color = t.text.copy(alpha = .75f)) }
                Box(
                    Modifier.clip(RoundedCornerShape(15.dp)).background(t.surface3)
                        .clickable { vm.openFix() }.padding(horizontal = 13.dp, vertical = 8.dp),
                ) { Text("분류 수정", style = fonts.meta.copy(fontWeight = FontWeight.Bold), color = t.text.copy(alpha = .75f)) }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(ui.albumDays, key = { it.day }) { g ->
                Column(Modifier.padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(g.day, style = fonts.body.copy(fontWeight = FontWeight.Bold), color = t.text)
                        Text(g.note, style = fonts.meta, color = t.text.copy(alpha = .38f))
                    }
                    for (row in g.photos.chunked(columns)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (p in row) AlbumThumb(
                                p, p.id in selected, selecting,
                                onClick = { if (selecting) vm.toggleAlbumSelect(p.id) else vm.openPhoto(p.id) },
                                modifier = Modifier.weight(1f),
                            )
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (ui.albumDays.isEmpty()) item {
                Text("불러오는 중…", style = fonts.caption, color = theme.textFaint,
                    modifier = Modifier.padding(34.dp))
            }
        }

        if (selecting) Box(
            Modifier.fillMaxWidth().height(64.dp).background(t.bg).padding(horizontal = 16.dp, vertical = 7.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(if (selected.isNotEmpty()) t.accentHi else t.surface4)
                .then(if (selected.isNotEmpty()) Modifier.clickable { onTrash(selected) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (selected.isEmpty()) "사진을 선택하세요" else "${selected.size}장 휴지통으로",
                style = fonts.itemTitle, color = if (selected.isEmpty()) t.textFaint else Color.White,
            )
        }
    }
}

@Composable
private fun AlbumThumb(
    photo: PhotoCard,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val t = theme
    Box(modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        Thumb(photo.uri, Modifier.matchParentSize())
        if (selected) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)))
        if (selecting) Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                .background(if (selected) t.accent else Color.Black.copy(alpha = .28f)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", style = fonts.badge, color = Color.White) }
    }
}

