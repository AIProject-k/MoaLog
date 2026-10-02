package com.moalog.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.moalog.personal.core.TimeSource
import com.moalog.personal.data.PhotoDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 전체 화면 사진 보기.
 *
 * 예전 상세 화면은 사진 **감상**이 아니라 정보 확인이 중심이었다 — 사진이 카드 하나
 * 크기로 붙고 그 아래 표가 길게 붙었다. 갤러리에서 사진을 열었을 때 사람이 하려는 일은
 * 그게 아니다. 여기서는 사진이 화면 전부를 쓰고, 좌우로 넘기고, 두 번 눌러 확대한다.
 * 기술 정보는 `정보`를 눌렀을 때만 올라온다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    if (ui.viewerIds.isEmpty()) return
    val actions = LocalActions.current
    val zone = ZoneId.systemDefault()

    val pager = rememberPagerState(
        initialPage = ui.viewerIndex.coerceIn(0, ui.viewerIds.lastIndex),
    ) { ui.viewerIds.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { vm.onViewerPage(it) }
    }

    // 확대한 동안에는 좌우 넘기기를 끈다. 안 끄면 사진을 끌어 옮기려다 다음 장으로 넘어간다.
    var zoomed by remember { mutableStateOf(false) }
    val id = ui.viewerIds.getOrNull(pager.currentPage)
    val favorite = id != null && id in ui.favorites

    Box(Modifier.fillMaxSize().background(t.bgDeep)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            key = { ui.viewerIds[it] },
        ) { page ->
            ZoomableImage(mediaUri(ui.viewerIds[page])) { s -> zoomed = s > 1.01f }
        }

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = .35f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton { vm.back() }
            Column(Modifier.weight(1f)) {
                Text(ui.openPhoto?.let { dateLine(it, zone) } ?: "", style = fonts.caption,
                    color = Color.White.copy(alpha = .9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pager.currentPage + 1} / ${ui.viewerIds.size}", style = fonts.meta,
                    color = Color.White.copy(alpha = .5f))
            }
            RoundAction(if (favorite) "♥" else "♡", tint = if (favorite) t.accentHi else Color.White) {
                id?.let(vm::toggleFavorite)
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            AnimatedVisibility(visible = ui.infoOpen) {
                ui.openPhoto?.let { InfoPanel(vm, ui, it, zone) }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .45f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerAction("공유") { id?.let { actions.share(listOf(it)) } }
                ViewerAction(if (ui.infoOpen) "정보 닫기" else "정보") { vm.toggleInfo() }
                ViewerAction("휴지통", danger = true) { id?.let { actions.trash(listOf(it)) } }
            }
        }
    }
}

/**
 * 확대·이동. 두 번 누르면 확대/원위치, 두 손가락으로 자유 확대.
 *
 * 제스처를 두 [pointerInput]으로 나눈 이유: 한 블록 안에서 탭과 변환을 같이 잡으면
 * 서로를 소비해 버려 둘 중 하나가 안 먹는다. 나누면 각자 자기 이벤트만 본다.
 */
@Composable
private fun ZoomableImage(uri: String, onScale: (Float) -> Unit) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1.01f) 1f else 2.5f
                    offset = Offset.Zero
                    onScale(scale)
                })
            }
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1.01f) offset + pan else Offset.Zero
                    onScale(scale)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(uri).size(1600).crossfade(120).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

/**
 * 정보 패널. **분류 점수·좌표·선명도 숫자는 `상세 정보` 안으로 넣었다.**
 * 첫 화면에 82% 같은 숫자가 있으면 사용자가 그것을 "정답 확률"로 읽는데,
 * 실제로는 프롬프트와의 유사도라서 그런 뜻이 아니다.
 */
@Composable
private fun InfoPanel(vm: AppViewModel, ui: AppViewModel.Ui, p: PhotoDetail, zone: ZoneId) {
    val t = theme
    Column(
        Modifier.fillMaxWidth().heightIn(max = 340.dp)
            .background(t.bg.copy(alpha = .96f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val shown = p.tags.filter { it.source != "rule" }
        if (shown.isNotEmpty()) {
            SectionLabel("이 사진의 분류")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(shown) { tag ->
                    Chip(
                        label = if (tag.source == "user") "#${tag.label} · 고정" else "#${tag.label}",
                        tinted = tag.source == "user",
                    ) { vm.pinTag(p.id, tag.label) }
                }
            }
            Text(
                "태그를 누르면 그 분류를 고정합니다. 고정한 분류는 다시 분석해도 유지됩니다.",
                style = fonts.meta, color = t.textMuted,
            )
        }

        Text(
            if (ui.detailsOpen) "상세 정보 닫기" else "상세 정보",
            style = fonts.caption.copy(fontWeight = FontWeight.Bold), color = t.accentHi,
            modifier = Modifier.clickable { vm.toggleDetails() },
        )
        if (ui.detailsOpen) Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            for ((k, v) in facts(p, zone)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(k, style = fonts.chip, color = t.text.copy(alpha = .42f),
                        modifier = Modifier.width(74.dp))
                    Text(v, style = fonts.bodySm, color = t.textStrong, modifier = Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.divider))
            }
            Text(
                "분류 옆의 %는 정답 확률이 아니라 기준 문장과 얼마나 닮았는지의 값입니다.",
                style = fonts.meta, color = t.textFaint, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ViewerAction(text: String, danger: Boolean = false, onClick: () -> Unit) = Text(
    text,
    style = fonts.caption.copy(fontWeight = FontWeight.Bold),
    color = if (danger) theme.accentHi else Color.White.copy(alpha = .9f),
    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 8.dp),
)

@Composable
private fun RoundAction(glyph: String, tint: Color, onClick: () -> Unit) = Box(
    Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f))
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(glyph, style = fonts.body, color = tint) }

private fun dateLine(p: PhotoDetail, zone: ZoneId): String {
    val t = p.takenAt ?: return "날짜 미상"
    val d = Instant.ofEpochSecond(t).atZone(zone)
    val s = d.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 a h:mm"))
    // 촬영시각인지 파일시각인지 밝힌다 — 받아 둔 사진은 시각이 촬영시점이 아니다
    return if (p.takenAtSource == TimeSource.EXIF) s else "$s · 파일 시각"
}

/** 아는 것만. 모르는 항목은 줄을 만들지 않는다. */
private fun facts(p: PhotoDetail, zone: ZoneId): List<Pair<String, String>> = buildList {
    p.tags.filter { it.source == "clip" }.take(3).takeIf { it.isNotEmpty() }?.let { clip ->
        add("장면" to clip.joinToString(", ") { tag ->
            tag.score?.let { "${tag.label} ${(it * 100).toInt()}%" } ?: tag.label
        })
    }
    p.tags.firstOrNull { it.source == "rule" }?.let { add("출처" to it.label) }
    p.displayName?.let { add("파일" to it) }
    p.cameraModel?.let { add("카메라" to it) }
    if (p.lat != null && p.lon != null) add("위치" to "%.5f, %.5f".format(p.lat, p.lon))
    if (p.width > 0) add("크기" to "${p.width}×${p.height} · ${p.bytes / 1024}KB")
    p.blurScore?.let { add("선명도" to "%.0f".format(it)) }
}
