package com.moalog.personal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moalog.personal.data.PhotoCard

/*
 * 사진 탭과 앨범 상세가 함께 쓰는 격자 · 선택 모드 막대. 조작이 화면마다 다르면 사용자가 매번 다시 배운다.
 */

const val GRID_COLUMNS = 3

/** 보기 설정에서 고를 수 있는 한 줄 칸 수. */
val GRID_CHOICES = listOf(2, 3, 4)

/** 격자를 한 줄씩 쪼갠 목록. 머리글 한 줄 + 사진 3칸 줄들. `month`는 스크러버가 지금 어느 달인지 알려 주는 데 쓴다. */
sealed interface GridRow {
    val key: String
    val month: String
}

class HeaderRow(val group: AppViewModel.DayGroup) : GridRow {
    override val key = "h:${group.photos.first().id}"
    override val month = group.month
}

class CellsRow(val photos: List<PhotoCard>, override val month: String, val first: Boolean) : GridRow {
    override val key = "r:${photos.first().id}"
}

/** [columns]칸짜리 줄로 나눈다. 머리글이 없는 묶음(날짜순이 아닌 정렬)은 머리글 줄을 만들지 않는다. */
fun buildRows(days: List<AppViewModel.DayGroup>, columns: Int = GRID_COLUMNS): List<GridRow> = buildList {
    for (g in days) {
        if (g.photos.isEmpty()) continue
        if (g.day.isNotEmpty()) add(HeaderRow(g))
        g.photos.chunked(columns).forEachIndexed { i, chunk -> add(CellsRow(chunk, g.month, i == 0)) }
    }
}

/**
 * 격자 줄을 LazyColumn에 붙인다. 칸 사이는 3dp. **길게 누르면 선택 모드**로 들어간다 — 일반 갤러리의
 * 몸에 밴 조작이라 선택 버튼을 찾아 누르는 것보다 한 단계 짧다.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.gridRows(
    rows: List<GridRow>,
    selecting: Boolean,
    selected: Set<Long>,
    favorites: Set<Long>,
    onOpen: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    columns: Int = GRID_COLUMNS,
) {
    items(rows, key = { it.key }, contentType = { if (it is HeaderRow) 0 else 1 }) { row ->
        when (row) {
            is HeaderRow -> DayHeader(row.group.day, row.group.note)
            is CellsRow -> Row(
                Modifier.fillMaxWidth().padding(top = if (row.first) 0.dp else 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (p in row.photos) MediaCell(
                    p,
                    Modifier.weight(1f).combinedClickable(
                        onClick = { if (selecting) onToggle(p.id) else onOpen(p.id) },
                        onLongClick = { onLongPress(p.id) },
                    ),
                    mark = if (selecting) CellMark.Select(p.id in selected) else CellMark.None,
                    favorite = p.id in favorites,
                    scene = true,
                )
                repeat(columns - row.photos.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 목록 끝이 보이면 다음 페이지를 부른다. 스크롤이 멈춘 뒤 부르면 이미 늦다. */
@Composable
fun LoadMoreWhenNearEnd(state: LazyListState, threshold: Int = 8, onLoad: () -> Unit) {
    val near by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(state) { snapshotFlow { near }.collect { if (it) onLoad() } }
}

/** 지금 화면 맨 위에 보이는 줄이 속한 달. 스크러버의 말풍선이 쓴다. */
@Composable
fun monthAtTop(state: LazyListState, rows: List<GridRow>): String? {
    val byKey = remember(rows) { rows.associateBy { it.key } }
    val month by remember(byKey) {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { byKey[it.key]?.month } }
    }
    return month
}

fun monthLabel(ym: String): String {
    if (ym == "none") return "날짜 미상"
    val parts = ym.split("-")
    if (parts.size != 2) return ym
    return "${parts[0]}년 ${parts[1].trimStart('0')}월"
}

// ---------------------------------------------------------------- 선택 모드 막대

/** 선택 모드의 상단 바: ✕ + "N개 선택" + 구성·범위 + "전체 선택". 배경이 한 단 밝아져 일반 화면과 구별된다. */
@Composable
fun SelectionTopBar(title: String, sub: String, allSelected: Boolean, onCancel: () -> Unit, onSelectAll: () -> Unit) {
    TopRow(height = 64.dp, background = theme.surface1) {
        IconBtn(I.Close, "선택 해제", onCancel)
        TitleBlock(title, sub, Modifier.weight(1f).padding(start = 4.dp), titleStyle = fonts.header)
        TextBtn(
            if (allSelected) "선택 해제" else "전체 선택", onSelectAll,
            color = theme.accent, style = fonts.label, horizontal = 12.dp,
        )
    }
}

/** "사진 2장 · 영상 1개 · 화면에 불러온 111개 중에서". 전체 선택이 무엇을 고르는지 숨기지 않는다. */
fun selectionSub(selected: Set<Long>, loaded: Int): String {
    val videos = selected.count { it < 0 }
    val photos = selected.size - videos
    val mix = listOfNotNull(
        photos.takeIf { it > 0 }?.let { "사진 ${it}장" },
        videos.takeIf { it > 0 }?.let { "영상 ${it}개" },
    ).joinToString(" · ")
    val range = "화면에 불러온 ${"%,d".format(loaded)}개 중에서"
    return if (mix.isEmpty()) range else "$mix · $range"
}

/**
 * 선택 후 할 수 있는 동작 목록. 앨범 상세에서는 그 앨범이 허락하는 "빼기"가 더해지고(즐겨찾기는 "즐겨찾기 해제"),
 * 즐겨찾기 앨범 안에서는 "즐겨찾기" 자체를 뺀다.
 */
@Composable
fun selectionActions(vm: AppViewModel, ui: AppViewModel.Ui, album: AppViewModel.Album?): List<SelectAction> {
    val actions = LocalActions.current
    val has = ui.selected.isNotEmpty()
    return buildList {
        if (album?.isFavorites != true) add(SelectAction("즐겨찾기", I.Heart, has) { vm.favoriteSelected() })
        add(SelectAction("공유", I.Share, has) { actions.share(ui.selected) })
        add(SelectAction("앨범에 추가", I.Plus, has) { vm.openPicker() })
        if (album != null && album.removable) add(
            SelectAction(if (album.isFavorites) "즐겨찾기 해제" else "앨범에서 빼기", I.Remove, has) { vm.removeSelectedFromAlbum() },
        )
    }
}

/** 하단 선택 동작 하나(아이콘 + 라벨). 아무것도 안 골랐으면 흐리게 보이고 눌러도 아무 일 없다. */
class SelectAction(val label: String, val icon: Int, val enabled: Boolean, val onClick: () -> Unit)

/**
 * 선택 후 할 수 있는 일. **가벼운 동작(즐겨찾기·공유·추가·빼기)은 왼쪽 아이콘 묶음, 휴지통은 구분선 너머
 * 오른쪽 끝 붉은 알약 하나** — 묶음에서 빼는 것과 기기에서 지우는 것은 사용자에게 완전히 다른 행동이다.
 */
@Composable
fun SelectionBottomBar(
    actions: List<SelectAction>,
    trashLabel: String,
    trashEnabled: Boolean,
    onTrash: () -> Unit,
) {
    val hair = theme.hair
    Column(
        Modifier.fillMaxWidth().background(theme.surface1)
            .drawBehind { drawLine(hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding().padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            for (a in actions) Column(
                Modifier.height(64.dp).alpha(if (a.enabled) 1f else .4f)
                    .let { if (a.enabled) it.tap(a.onClick, a.label) else it }
                    .padding(horizontal = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Ic(a.icon, size = 24.dp)
                Text(a.label, style = fonts.barLabel, color = theme.text1, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Box1pxDivider()
            Spacer(Modifier.width(8.dp))
            Pill(
                trashLabel, onTrash, icon = I.Trash, enabled = trashEnabled,
                bg = if (trashEnabled) theme.danger else theme.surface3,
                fg = if (trashEnabled) Color.White else theme.text3,
                style = fonts.label, horizontal = 14.dp,
            )
        }
    }
}

@Composable
private fun Box1pxDivider() {
    androidx.compose.foundation.layout.Box(Modifier.width(1.dp).height(32.dp).background(Color(0x1FFFFFFF)))
}
