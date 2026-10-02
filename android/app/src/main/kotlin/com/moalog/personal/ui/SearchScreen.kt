package com.moalog.personal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moalog.personal.core.VideoSampling
import com.moalog.personal.data.MediaKind
import com.moalog.personal.data.PhotoCard

/**
 * 검색. 탭 화면이므로 뒤로 화살표가 없다. 입력창 아래가 상태에 따라 바뀐다:
 * 처음(안내·추천) → 찾는 중 → 결과(해석 칩 · 요약 · 가까운 결과 · 접힌 그 밖의 결과) / 결과 없음.
 *
 * **자동 저장은 없다.** "앨범으로 모으기"는 확인 모드로 들어가 빼고 싶은 것을 누르게 하고, 이름을 정해 저장한다.
 */
@Composable
fun SearchScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    if (ui.selectionMode) { ReviewMode(vm, ui); return }

    val t = theme
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(ui.searchFocus) { if (ui.searchFocus) { focus.requestFocus(); vm.consumeSearchFocus() } }

    val hasResults = ui.results.isNotEmpty()
    val initial = !ui.searching && ui.parsed == null && !hasResults
    val none = !ui.searching && ui.parsed != null && !hasResults

    Column(Modifier.fillMaxSize()) {
        // ---- 입력창
        Row(
            Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(t.surface2)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Ic(I.Search, size = 22.dp, tint = t.text2, modifier = Modifier.tap({ vm.search(); focusManager.clearFocus() }, "검색", Role.Button))
            BasicTextField(
                value = ui.query, onValueChange = vm::onQuery, singleLine = true,
                textStyle = fonts.input.copy(color = t.text1), cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(); focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (ui.query.isEmpty()) Text("사진·영상 속 장면 찾기", style = fonts.input, color = t.text3)
                        inner()
                    }
                },
            )
            if (ui.query.isNotEmpty()) IconBtn(I.Close, "지우기", { vm.clearSearch() }, tint = t.text3, size = 20.dp, modifier = Modifier.size(44.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when {
                initial -> item(key = "initial") { SearchInitial(vm, ui) }
                ui.searching -> item(key = "loading") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Spinner()
                        Text("찾고 있습니다…", style = fonts.bodySm, color = t.text2)
                    }
                }
                else -> {
                    ui.parsed?.chips?.takeIf { it.isNotEmpty() }?.let { chips ->
                        item(key = "chips") { InterpretationChips(chips, ui.searchOff, vm::toggleChip) }
                    }
                    if (none) item(key = "none") {
                        EmptyState(I.Search, "조건에 맞는 사진·영상을 찾지 못했습니다. 다른 말로 적어보세요.", top = 72.dp,
                            titleStyle = fonts.body, titleColor = t.text2)
                    } else searchResults(vm, ui)
                }
            }
            item(key = "end") { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/** 처음 화면: 안내 한 줄 + 추천 검색어 + 자주 나오는 분류 + 프라이버시 한 줄. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchInitial(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Prose("찾고 싶은 장면을 문장으로 적어보세요. 날짜, 장소, 사물을 섞어도 됩니다.", style = fonts.bodySm, color = t.text2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoOnlyChip(ui.searchVideosOnly, large = true) { vm.toggleVideoSearch() }
            for (s in SUGGESTIONS) Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.surface2)
                    .clickable { vm.pickSuggestion(s) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(s, style = fonts.chipRegular, color = t.text1) }
        }
        val tags = vm.frequentTags()
        if (tags.isNotEmpty()) {
            Text("자주 나오는 분류", style = fonts.metaStrong, color = t.text3, modifier = Modifier.padding(top = 4.dp))
            FlowRow(Modifier.offset(y = (-6).dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in tags) Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.surface1)
                        .clickable { vm.pickSuggestion(tag) }.padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Ic(I.Ai, size = 15.dp, tint = t.text2)
                    Text(tag, style = fonts.chipRegular, color = t.text2)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ic(I.Offline, size = 15.dp, tint = t.text3)
            Text("검색어는 이 휴대폰 안에서만 처리됩니다.", style = fonts.micro, color = t.text3)
        }
    }
}

private val SUGGESTIONS = listOf("작년 여름 바다", "밤에 찍은 사진", "강아지가 나오는 영상", "지난달 음식")

/** "동영상만" 토글. 켜면 강아지·바다처럼 영상에만 맞추고, 끄면 윤곽선만 남는다. */
@Composable
private fun VideoOnlyChip(on: Boolean, large: Boolean, onClick: () -> Unit) {
    val t = theme
    Row(
        Modifier.height(if (large) 40.dp else 32.dp).clip(RoundedCornerShape(if (large) 20.dp else 16.dp))
            .background(if (on) t.accent else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(1.dp, t.ringStrong, RoundedCornerShape(if (large) 20.dp else 16.dp)))
            .clickable(onClick = onClick)
            .padding(start = if (large) 10.dp else 10.dp, end = if (large) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 5.dp),
    ) {
        val fg = if (on) t.onAccent else t.text2
        Ic(I.Video, size = if (large) 16.dp else 15.dp, tint = fg)
        Text("동영상만", style = if (large) fonts.chip else fonts.chipSm.copy(fontWeight = FontWeight.Normal), color = fg)
    }
}

/**
 * "이렇게 이해했습니다". 점선 칩 = 앱이 문장에서 읽어 낸 조건이다. **눌러서 끌 수 있다** — 잘못 해석했으면
 * 사용자가 끄고, 그러면 같은 질의를 그 조건 없이 다시 찾는다(조용히 틀리는 것보다 낫다).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterpretationChips(chips: List<String>, off: Set<String>, onToggle: (String) -> Unit) {
    val t = theme
    FlowRow(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("이렇게 이해했습니다", style = fonts.micro, color = t.text3, modifier = Modifier.padding(top = 7.dp, end = 2.dp))
        for (c in chips) {
            val disabled = c in off
            val color = if (disabled) t.hint else t.text2
            Row(
                Modifier.height(32.dp).dashedBorder(if (disabled) t.track else t.hint, 16.dp).clip(RoundedCornerShape(16.dp))
                    .tap({ onToggle(c) }, if (disabled) "$c 켜기" else "$c 끄기").padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    c, style = fonts.chipSm.copy(fontWeight = FontWeight.Normal), color = color,
                    textDecoration = if (disabled) TextDecoration.LineThrough else null,
                )
                Ic(I.Close, size = 12.dp, tint = color)
            }
        }
    }
}

/** 영상 칸 아래 띠에 적는 맞은 장면: "약 0:36 외 2곳". 영상은 5초 간격 프레임이라 시각은 근사값이다. */
private fun sceneCaption(p: PhotoCard, hits: List<Long>): String? {
    if (p.mediaKind != MediaKind.VIDEO) return null
    // 장면 시각을 모르는 영상(필터만으로 찾은 것)은 띠 없이 일반 영상 배지(▶ + 길이)로 둔다.
    val first = hits.firstOrNull() ?: p.sceneTimeMs ?: return null
    return "약 ${VideoSampling.time(first)}" + if (hits.size > 1) " 외 ${hits.size - 1}곳" else ""
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(vm: AppViewModel, ui: AppViewModel.Ui) {
    val close = ui.results.take(ui.closeCount)
    val other = ui.results.drop(ui.closeCount)
    val shown = close + if (ui.othersOpen) other else emptyList()
    val ids = shown.map(PhotoCard::id)

    item(key = "summary") { SummaryCard(vm, ui) }
    item(key = "close-head") {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("가까운 결과", style = fonts.label, color = theme.text1)
            Text("${close.size}개", style = fonts.meta, color = theme.text3)
        }
    }
    resultRows(close, ui, ids, vm)
    if (other.isNotEmpty()) {
        item(key = "others-toggle") {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(Radius.thumb)).background(theme.surface1)
                    .clickable { vm.toggleOthers() }.padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("그 밖의 결과 ${other.size}개 · 관련이 낮을 수 있습니다", style = fonts.chipRegular, color = theme.text2, modifier = Modifier.weight(1f))
                Ic(I.ChevronDown, size = 20.dp, tint = theme.text2, modifier = Modifier.graphicsLayer { rotationZ = if (ui.othersOpen) 180f else 0f })
            }
        }
        if (ui.othersOpen) {
            item(key = "others-gap") { Spacer(Modifier.height(8.dp)) }
            resultRows(other, ui, ids, vm, dim = true)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultRows(
    list: List<PhotoCard>, ui: AppViewModel.Ui, ids: List<Long>, vm: AppViewModel, dim: Boolean = false,
) {
    items(list.chunked(GRID_COLUMNS), key = { row -> (if (dim) "o:" else "c:") + row.first().id }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp).graphicsLayer { alpha = if (dim) .85f else 1f },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (p in row) MediaCell(
                p, Modifier.weight(1f).clickable { vm.openViewer(ids, p.id) },
                scene = true, caption = sceneCaption(p, ui.sceneHits[p.id].orEmpty()),
            )
            repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 검색 요약 카드: "검색 요약" + 동영상만 + 한 문장 + 앨범으로 모으기 + 영상 안내. */
@Composable
private fun SummaryCard(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card)).background(t.surface1).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("검색 요약", style = fonts.badge.copy(letterSpacing = 0.88.sp), color = t.text3, modifier = Modifier.weight(1f))
            VideoOnlyChip(ui.searchVideosOnly, large = false) { vm.toggleVideoSearch() }
        }
        Prose(ui.resultSummary, style = fonts.body21, color = t.text1)
        if (ui.closeCount > 0) Pill(
            "앨범으로 모으기", vm::startSearchReview, bg = t.surface3, fg = t.text1, icon = I.Album,
            height = 40.dp, style = fonts.bodySmStrong, horizontal = 16.dp,
        )
        Text("영상은 5초 간격 장면으로 찾습니다. 표시 시각은 근사값입니다.", style = fonts.micro, color = t.text3)
    }
}

// ---------------------------------------------------------------- 확인 모드

/**
 * 앨범으로 모으기 확인 모드. 일반 선택 모드와 확실히 구별된다: 남색 머리띠 + 앨범 아이콘 제목 + 항상 떠 있는 이름 입력 시트.
 * 기본값은 모두 담김이고, 뺀 결과는 "뺌"으로 덮는다. **저장 버튼을 누르기 전에는 아무것도 저장하지 않는다.**
 */
@Composable
private fun ReviewMode(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val close = ui.results.take(ui.closeCount)
    val ids = close.map(PhotoCard::id)
    val n = close.count { it.id in ui.selected }
    var name by remember(ui.query) { mutableStateOf(ui.query.trim().take(20)) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(t.reviewBar)) {
            TopRow(height = 64.dp) {
                IconBtn(I.Close, "닫기", vm::cancelSelection)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ic(I.Album, size = 20.dp, tint = t.accent)
                        Text("앨범으로 모으기", style = fonts.header, color = t.text1)
                    }
                    Text("${close.size}개 중 ${n}개 담김", style = fonts.meta, color = t.text2)
                }
            }
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Ic(I.Info, size = 16.dp, tint = t.reviewText)
                Text("앨범에 넣지 않을 결과를 눌러 빼세요.", style = fonts.caption.copy(lineHeight = androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.reviewText)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 3.dp)) {
            items(close.chunked(GRID_COLUMNS), key = { row -> "rv:${row.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (p in row) MediaCell(
                        p, Modifier.weight(1f).clickable { vm.toggleSelect(p.id) },
                        mark = CellMark.Review(p.id in ui.selected), scene = true,
                        caption = sceneCaption(p, ui.sceneHits[p.id].orEmpty()),
                    )
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // 이름 시트
        Column(
            Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)).background(t.surface1)
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("앨범 이름", style = fonts.metaStrong, color = t.text3)
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radius.thumb)).background(t.surface2)
                    .border(2.dp, t.accent, RoundedCornerShape(Radius.thumb)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = name, onValueChange = { name = it.take(20) }, singleLine = true,
                    textStyle = fonts.input.copy(color = t.text1), cursorBrush = SolidColor(t.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("영상은 찾은 장면 시각이 함께 저장되어, 앨범에서 열어도 그 장면부터 재생됩니다.", style = fonts.micro, color = t.text3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn("취소", vm::cancelSelection, color = t.text2, style = fonts.button, horizontal = 20.dp)
                Pill(
                    if (n > 0) "${n}개로 앨범 만들기" else "결과를 고르세요", { vm.saveSearchAlbum(name) }, Modifier.weight(1f),
                    bg = if (n > 0) t.accent else t.surface3, fg = if (n > 0) t.onAccent else t.text3,
                    enabled = n > 0, style = fonts.button,
                )
            }
        }
    }
}
