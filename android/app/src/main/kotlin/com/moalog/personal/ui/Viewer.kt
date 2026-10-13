package com.moalog.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.moalog.personal.core.TimeSource
import com.moalog.personal.core.VideoSampling
import com.moalog.personal.data.PhotoDetail
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * 전체 화면 사진·영상 보기. 감상이 먼저고 정보는 부른 사람에게만 올라온다.
 *
 * 디자인(`MoaLog App.dc.html`)의 규칙:
 *  - 사진이 화면 전부를 쓰고, 한 번 탭하면 상·하단 컨트롤이 150ms 페이드로 숨고 다시 탭하면 돌아온다.
 *  - **아래로 밀면 닫히고, 위로 밀면 정보 시트가 올라온다.** 정보가 열리면 사진은 위쪽 띠로 줄어든다.
 *  - 영상은 사진과 **같은 뼈대**(상단·하단 위치 동일)이고, 영상 위에 −5초·재생·+15초 세 버튼과 전용 탐색 막대가 얹힌다.
 *    검색에서 장면으로 열면 그 장면부터 자동 재생하고, 아래 "찾은 장면" 칩으로 다른 장면에 바로 뛴다.
 *  - 전체 화면은 가로로 돌려 영상만 채운다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val ids = ui.viewerIds
    if (ids.isEmpty()) return
    val actions = LocalActions.current
    val controls = ui.videoControls
    val fullscreen = controls.fullscreen
    val info = ui.infoOpen
    val zone = remember { ZoneId.systemDefault() }

    val pager = rememberPagerState(initialPage = ui.viewerIndex.coerceIn(0, ids.lastIndex)) { ids.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { vm.onViewerPage(it) } }
    val id = ids.getOrNull(pager.currentPage)
    val isVideo = id != null && id < 0
    val favorite = id != null && id in ui.favorites

    var chrome by remember(id) { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    // 찾은 장면으로 열었거나 칩을 눌렀을 때 그 장면 시각. 직접 되감거나 끌면 지운다(왼쪽 위 "약 0:36 장면부터 재생 중" 표시용).
    var lastScene by remember(id) { mutableStateOf(id?.let { ui.sceneTimes[it] }) }

    FullscreenEffect(fullscreen)

    val player = if (isVideo && id != null) rememberPlayer(
        mediaId = id, uri = mediaUri(id), startMs = ui.sceneTimes[id] ?: 0L, controls = controls,
        onPlaybackChanged = vm::saveVideoPlayback, onPositionChanged = vm::saveVideoPosition,
    ) else null
    val playing = player?.playing == true

    // 재생 중이면 컨트롤을 2.6초 뒤 숨기고, 멈추면 다시 보인다(검은 화면만 남으면 누를 곳을 모른다).
    LaunchedEffect(playing, chrome, info) {
        if (playing && chrome && !info) { delay(2600); chrome = false }
    }
    LaunchedEffect(playing) { if (!playing) chrome = true }

    // 한 칸 위/아래 치수. 디자인 기준 891dp 화면: 상단 바 끝 84, 정보 시트 시작 420, 영상 띠 높이 400.
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bandTop = statusTop + 56.dp
    val sheetTop = screenH * 0.4714f
    val stageH = (sheetTop - 20.dp - bandTop).coerceAtLeast(120.dp)
    val bandFull = minOf(400.dp, screenH * 0.45f)
    val bandH by animateDpAsState(if (info) stageH else bandFull, tween(250), label = "band")
    val stageTopPad by animateDpAsState(if (info) bandTop else 0.dp, tween(250), label = "stageTop")
    val stageBottomPad by animateDpAsState(if (info) screenH - (bandTop + stageH) else 0.dp, tween(250), label = "stageBottom")

    Box(Modifier.fillMaxSize().background(t.viewer)) {
        if (fullscreen && isVideo && player != null) {
            FullscreenVideo(player, ui.openPhoto?.let { dateLine(it, zone) }.orEmpty(), chrome, { chrome = !chrome },
                controls.muted, vm::toggleVideoMute, controls.speed, vm::cycleVideoSpeed) { vm.setVideoFullscreen(false) }
            return@Box
        }

        // ---- 본문: 좌우로 넘기는 페이지
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            key = { ids[it] },
        ) { page ->
            val mediaId = ids[page]
            val gestures = Modifier.viewerSwipes(
                onTap = { chrome = !chrome },
                onSwipeUp = { if (!info) vm.toggleInfo() },
                onSwipeDown = { if (info) vm.toggleInfo() else if (!zoomed) vm.back() },
            )
            if (mediaId < 0) {
                Box(Modifier.fillMaxSize().then(gestures)) {
                    Box(Modifier.padding(top = bandTop).fillMaxWidth().height(bandH)) {
                        if (page == pager.currentPage && player != null) {
                            VideoBand(
                                player = player, chromeVisible = chrome, failed = player.failed,
                                scene = lastScene?.takeIf { player.positionMs < it + 5000 },
                                onRewind = { lastScene = null; player.skip(-5000) },
                                onForward = { lastScene = null; player.skip(15000) },
                                onToggle = { player.toggle() },
                                duration = ui.openPhoto?.durationMs,
                            )
                        } else PlainThumb(mediaUri(mediaId), Modifier.fillMaxSize(), px = 720, background = Color.Black)
                    }
                }
            } else Box(Modifier.fillMaxSize().padding(top = stageTopPad, bottom = stageBottomPad)) {
                ZoomableImage(
                    uri = mediaUri(mediaId),
                    onZoom = { s ->
                        zoomed = s > 1.01f
                        chrome = !zoomed || info
                    },
                    onTap = { chrome = !chrome },
                    onSwipeUp = { if (!info) vm.toggleInfo() },
                    onSwipeDown = { if (info) vm.toggleInfo() else if (!zoomed) vm.back() },
                )
            }
        }

        // ---- 영상 아래의 탐색 막대 · 시간 · 음소거 · 전체 화면 · 찾은 장면
        if (isVideo && player != null && !info && !(player.failed)) VideoDock(
            player = player, top = bandTop + bandH + 12.dp, muted = controls.muted, speed = controls.speed,
            scenes = ui.sceneHits[id].orEmpty(), lastScene = lastScene,
            onMute = vm::toggleVideoMute, onSpeed = vm::cycleVideoSpeed, onFullscreen = { vm.setVideoFullscreen(true) },
            onSeek = { lastScene = null; player.seekTo(it) },
            onScene = { ms -> lastScene = ms; vm.seekVideo(ms) },
        )

        // ---- 상단 바(그라데이션 위에 얹힌다)
        AnimatedVisibility(
            visible = chrome || info,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().height(statusTop + 120.dp)
                        .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))),
                )
                TopRow(modifier = Modifier.padding(horizontal = 4.dp), height = 56.dp) {
                    IconBtn(I.Back, "뒤로", vm::back)
                    TitleBlock(
                        ui.openPhoto?.let { dateLine(it, zone) } ?: "", "${pager.currentPage + 1} / ${ids.size}",
                        Modifier.weight(1f), titleStyle = fonts.label, center = true,
                    )
                    IconBtn(
                        if (favorite) I.HeartFilled else I.Heart, "즐겨찾기", { id?.let(vm::toggleFavorite) },
                        tint = if (favorite) t.dangerText else t.text1,
                    )
                }
            }
        }

        // ---- 하단 동작: 공유 · 정보 · 휴지통. 사진은 컨트롤과 함께 숨고, 영상은 늘 보인다.
        AnimatedVisibility(
            visible = !info && (chrome || isVideo),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000))))
                    .navigationBarsPadding().height(72.dp),
            ) {
                BottomAction(I.Share, "공유", Modifier.weight(1f)) { id?.let { actions.share(listOf(it)) } }
                BottomAction(I.Info, "정보", Modifier.weight(1f)) { vm.toggleInfo() }
                BottomAction(I.Trash, "휴지통", Modifier.weight(1f)) { id?.let { actions.trash(listOf(it)) } }
            }
        }

        // ---- 정보 시트
        AnimatedVisibility(
            visible = info,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(250)) { it }, exit = slideOutVertically(tween(200)) { it },
        ) {
            InfoSheet(vm, ui, Modifier.fillMaxWidth().height(screenH - sheetTop), zone)
        }
    }
}

// ---------------------------------------------------------------- 제스처

/**
 * 뷰어 제스처: 탭 · 위/아래로 밀기. 좌우로 넘기는 건 페이저에 맡기려고 **가로 움직임은 소비하지 않는다**.
 * 밀기는 놓을 때 판정한다(아래로 90dp 이상 / 위로 70dp 이상, 세로가 가로보다 클 때).
 */
@Composable
private fun Modifier.viewerSwipes(
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
): Modifier {
    // 제스처 코루틴은 한 번 시작되면 첫 람다를 붙들고 있다. 정보 시트가 열렸는지 같은 최신 상태를 보려면 이렇게 넘긴다.
    val tap by rememberUpdatedState(onTap)
    val up by rememberUpdatedState(onSwipeUp)
    val down by rememberUpdatedState(onSwipeDown)
    return pointerInput(Unit) {
    val down90 = 90.dp.toPx()
    val up70 = 70.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var dx = 0f; var dy = 0f
        do {
            val event = awaitPointerEvent()
            val c = event.changes.firstOrNull() ?: break
            dx += c.position.x - c.previousPosition.x
            dy += c.position.y - c.previousPosition.y
        } while (event.changes.any { it.pressed })
        when {
            abs(dx) < 8.dp.toPx() && abs(dy) < 8.dp.toPx() -> tap()
            abs(dy) > abs(dx) && dy > down90 -> down()
            abs(dy) > abs(dx) && dy < -up70 -> up()
        }
    }
    }
}

// ---------------------------------------------------------------- 사진

/**
 * 확대·이동. 두 번 누르면 2.5배/원위치, 두 손가락으로 1~6배. **확대하지 않았을 때는 한 손가락 움직임을 소비하지
 * 않는다** — 소비하면 좌우로 넘기기(페이저)와 위·아래로 밀기가 먹지 않는다.
 */
@Composable
private fun ZoomableImage(
    uri: String,
    onZoom: (Float) -> Unit,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val zoomCb by rememberUpdatedState(onZoom)
    val tapCb by rememberUpdatedState(onTap)
    val upCb by rememberUpdatedState(onSwipeUp)
    val downCb by rememberUpdatedState(onSwipeDown)
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var pinching by remember(uri) { mutableStateOf(false) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val shownScale by animateFloatAsState(scale, if (pinching) snap() else tween(250), label = "zoom")

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(uri) {
                val slop = viewConfiguration.touchSlop
                val down90 = 90.dp.toPx(); val up70 = 70.dp.toPx()
                var lastTapAt = 0L
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f; var dy = 0f
                    var multi = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            multi = true; pinching = true
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1.01f) clamp(offset + event.calculatePan(), scale) else Offset.Zero
                            zoomCb(scale)
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val c = event.changes.first()
                            val d = c.position - c.previousPosition
                            dx += d.x; dy += d.y
                            if (scale > 1.01f && (abs(dx) + abs(dy) > slop)) {
                                offset = clamp(offset + d, scale)
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    pinching = false
                    if (multi) return@awaitEachGesture
                    val tap = abs(dx) < 8.dp.toPx() && abs(dy) < 8.dp.toPx()
                    when {
                        tap -> {
                            val now = first.uptimeMillis
                            if (now - lastTapAt < viewConfiguration.doubleTapTimeoutMillis) {
                                scale = if (scale > 1.01f) 1f else 2.5f
                                offset = Offset.Zero
                                zoomCb(scale)
                                lastTapAt = 0L
                            } else { tapCb(); lastTapAt = now }
                        }
                        scale <= 1.01f && abs(dy) > abs(dx) && dy > down90 -> downCb()
                        scale <= 1.01f && abs(dy) > abs(dx) && dy < -up70 -> upCb()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(uri).size(1600).crossfade(120).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = shownScale; scaleY = shownScale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

// ---------------------------------------------------------------- 영상

/** 영상 띠: 영상 면 + (컨트롤이 보일 때) −5·재생·+15 + 장면 배지 + 재생 실패 안내. */
@Composable
private fun VideoBand(
    player: PlayerHandle,
    chromeVisible: Boolean,
    failed: Boolean,
    scene: Long?,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onToggle: () -> Unit,
    duration: Long?,
) {
    val t = theme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(
                Modifier.fillMaxWidth().height(231.dp).background(t.surface1),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Ic(I.Video, size = 36.dp, tint = t.hint)
                Text("이 기기에서 재생할 수 없는 동영상입니다.", style = fonts.body, color = t.text2, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp))
                Text(durationText(duration), style = fonts.meta, color = t.text3)
            }
            return@Box
        }
        VideoSurface(player, Modifier.fillMaxSize())
        if (scene != null) Row(
            Modifier.align(Alignment.TopStart).padding(10.dp).height(28.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0x99000000)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Ic(I.Scene, size = 13.dp, tint = Color.White)
            Text("약 ${VideoSampling.time(scene)} 장면부터 재생 중", style = fonts.metaStrong, color = Color.White, maxLines = 1)
        }
        AnimatedVisibility(visible = chromeVisible, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                SkipButton(I.Rewind, "5", "5초 뒤로", onRewind)
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color(0x8C000000)).clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) { Ic(if (player.playing) I.Pause else I.Play, size = 30.dp, tint = Color.White) }
                SkipButton(I.Forward, "15", "15초 앞으로", onForward)
            }
        }
    }
}

@Composable
private fun SkipButton(icon: Int, label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0x73000000)).tap(onClick, description),
        contentAlignment = Alignment.Center,
    ) {
        Ic(icon, size = 28.dp, tint = Color.White)
        Text(label, style = fonts.tiny, color = Color.White, modifier = Modifier.offset(y = 3.dp))
    }
}

/** 재생 속도 버튼. 누를 때마다 1× → 1.5× → 2× → 0.5× → 1×로 바뀐다. */
@Composable
private fun SpeedBtn(speed: Float, onClick: () -> Unit, color: Color) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(VideoPlayerControls.speedLabel(speed), style = fonts.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/** 영상 아래: 탐색 막대 + 시간 + 음소거·전체 화면 + (검색에서 열었으면) 찾은 장면 칩. */
@Composable
private fun VideoDock(
    player: PlayerHandle,
    top: Dp,
    muted: Boolean,
    speed: Float,
    scenes: List<Long>,
    lastScene: Long?,
    onMute: () -> Unit,
    onSpeed: () -> Unit,
    onFullscreen: () -> Unit,
    onSeek: (Long) -> Unit,
    onScene: (Long) -> Unit,
) {
    val t = theme
    val duration = player.durationMs.coerceAtLeast(1L)
    Column(Modifier.padding(top = top, start = 16.dp, end = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SeekBar(
            fraction = player.positionMs.toFloat() / duration,
            markers = scenes.take(3).map { (it.toFloat() / duration).coerceIn(0f, 1f) },
            onSeek = { onSeek((it * duration).toLong()) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                VideoSampling.time(player.positionMs), style = fonts.caption.copy(fontWeight = FontWeight.Medium), color = t.text1,
            )
            Text(" / ${VideoSampling.time(player.durationMs)}", style = fonts.caption.copy(fontWeight = FontWeight.Medium), color = t.text3, modifier = Modifier.weight(1f))
            SpeedBtn(speed, onSpeed, t.text1)
            IconBtn(if (muted) I.Mute else I.Volume, if (muted) "음소거 해제" else "음소거", onMute)
            IconBtn(I.Fullscreen, "전체 화면", onFullscreen, modifier = Modifier.offset(x = 12.dp))
        }
        if (scenes.isNotEmpty()) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("찾은 장면", style = fonts.metaStrong, color = t.text3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (ms in scenes.take(3)) {
                    val on = lastScene == ms
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) t.accent else t.surface2)
                            .clickable { onScene(ms) }.padding(start = 12.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (on) Ic(I.Play, size = 14.dp, tint = t.onAccent)
                        Text(
                            "약 ${VideoSampling.time(ms)}",
                            style = fonts.chipRegular.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (on) t.onAccent else t.text1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 전체 화면 영상. 기기를 가로로 돌렸고(시스템 바는 [FullscreenEffect]가 숨겼다), 영상이 화면을 채운다.
 * 컨트롤은 탭하면 150ms 페이드로 나타나고 재생 중에는 2.6초 뒤 숨는다.
 */
@Composable
private fun FullscreenVideo(
    player: PlayerHandle,
    title: String,
    chrome: Boolean,
    onTap: () -> Unit,
    muted: Boolean,
    onMute: () -> Unit,
    speed: Float,
    onSpeed: () -> Unit,
    onExit: () -> Unit,
) {
    val t = theme
    val duration = player.durationMs.coerceAtLeast(1L)
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var moved = false
        do { val e = awaitPointerEvent(); if (e.changes.any { (it.position - it.previousPosition).getDistance() > 8.dp.toPx() }) moved = true } while (e.changes.any { it.pressed })
        if (!moved) onTap()
    } }) {
        VideoSurface(player, Modifier.fillMaxSize())
        AnimatedVisibility(visible = chrome, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
                Text(title, style = fonts.label, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 18.dp))
                Box(
                    Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0x8C000000)).clickable { player.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Ic(if (player.playing) I.Pause else I.Play, size = 30.dp, tint = Color.White) }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${VideoSampling.time(player.positionMs)} / ${VideoSampling.time(player.durationMs)}",
                        style = fonts.caption.copy(fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1)
                    SeekBar(
                        fraction = player.positionMs.toFloat() / duration, markers = emptyList(),
                        onSeek = { player.seekTo((it * duration).toLong()) },
                        modifier = Modifier.weight(1f), trackColor = Color(0x4DFFFFFF),
                    )
                    SpeedBtn(speed, onSpeed, Color.White)
                    IconBtn(if (muted) I.Mute else I.Volume, if (muted) "음소거 해제" else "음소거", onMute)
                    IconBtn(I.FullscreenExit, "화면 복귀", onExit)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 하단 바 · 정보 시트

@Composable
private fun BottomAction(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().tap(onClick, label),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Ic(icon, size = 24.dp, tint = Color.White)
        Text(label, style = fonts.badge.copy(fontWeight = FontWeight.Normal), color = Color.White)
    }
}

/**
 * 정보 시트. **분류 점수·좌표·선명도 숫자는 `상세 정보` 안으로 넣었다.** 첫 화면에 82% 같은 숫자가 있으면
 * 사용자가 그것을 "정답 확률"로 읽는데, 실제로는 프롬프트와의 유사도라서 그런 뜻이 아니다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoSheet(vm: AppViewModel, ui: AppViewModel.Ui, modifier: Modifier, zone: ZoneId) {
    val t = theme
    val p = ui.openPhoto
    Column(
        modifier.clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)).background(t.surface1)
            .verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.hint))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이 사진·영상의 분류", style = fonts.item, color = t.text1, modifier = Modifier.weight(1f))
            Box(Modifier.offset(x = 12.dp)) { IconBtn(I.Close, "정보 닫기", vm::toggleInfo, tint = t.text2, size = 22.dp) }
        }
        if (p != null) {
            val shown = p.tags.filter { it.source != "rule" }.take(5)
            if (shown.isNotEmpty()) FlowRow(
                Modifier.offset(y = (-8).dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tag in shown) {
                    val pinned = tag.source == "user"
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (pinned) t.accent.copy(alpha = .14f) else t.surface3)
                            .then(if (pinned) Modifier.border(1.dp, t.accent, RoundedCornerShape(18.dp)) else Modifier)
                            .clickable { vm.pinTag(p.id, tag.label) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (pinned) Ic(I.Pin, size = 15.dp, tint = t.accent)
                        Text(tag.label, style = fonts.chip, color = if (pinned) t.accent else t.text1)
                        if (pinned) Text(" · 고정됨", style = fonts.chip.copy(fontWeight = FontWeight.Medium), color = t.accent)
                    }
                }
            }
            Prose("태그를 누르면 그 분류를 고정합니다. 고정한 분류는 다시 분석해도 유지됩니다.", style = fonts.note, color = t.text2)
            Hair()
            Row(
                Modifier.fillMaxWidth().height(44.dp).clickable { vm.toggleDetails() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("상세 정보", style = fonts.label, color = t.text1, modifier = Modifier.weight(1f))
                Ic(I.ChevronDown, size = 20.dp, tint = t.text2, modifier = Modifier.graphicsLayer { rotationZ = if (ui.detailsOpen) 180f else 0f })
            }
            if (ui.detailsOpen) {
                Column(Modifier.offset(y = (-6).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, v) in facts(p, zone)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(k, style = fonts.note.copy(lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text3, modifier = Modifier.width(56.dp))
                        Text(v, style = fonts.note.copy(lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.text1, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ic(I.Info, size = 14.dp, tint = t.text3, modifier = Modifier.padding(top = 2.dp))
                    Text("분류 옆의 %는 정답 확률이 아니라 기준 문장과 얼마나 닮았는지의 값입니다.", style = fonts.micro, color = t.text3)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** "2026년 9월 23일 오전 8:36". 촬영시각이 아니라 파일시각이면 그렇다고 밝힌다 — 받아 둔 사진은 시각이 촬영시점이 아니다. */
private fun dateLine(p: PhotoDetail, zone: ZoneId): String {
    val t = p.takenAt ?: return "날짜 미상"
    val d = Instant.ofEpochSecond(t).atZone(zone)
    val s = d.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 a h:mm", Locale.KOREAN))
    return if (p.takenAtSource == TimeSource.EXIF) s else "$s · 파일 시각"
}

/** 아는 것만. 모르는 항목은 줄을 만들지 않는다. */
private fun facts(p: PhotoDetail, zone: ZoneId): List<Pair<String, String>> = buildList {
    p.tags.filter { it.source == "clip" }.take(3).takeIf { it.isNotEmpty() }?.let { clip ->
        add("장면" to clip.joinToString(" · ") { tag ->
            tag.score?.let { "${tag.label} ${(it * 100).toInt()}%" } ?: tag.label
        })
    }
    p.tags.firstOrNull { it.source == "rule" }?.let { add("출처" to it.label) }
    p.displayName?.let { name -> add("파일" to if (p.bytes > 0) "$name · ${sizeText(p.bytes)}" else name) }
    p.cameraModel?.let { add("카메라" to it) }
    if (p.lat != null && p.lon != null) add("위치" to "%.4f, %.4f".format(p.lat, p.lon))
    if (p.width > 0) add("크기" to "${p.width} × ${p.height}")
    if (p.mediaKind == com.moalog.personal.data.MediaKind.VIDEO) p.durationMs?.takeIf { it > 0 }?.let { add("길이" to VideoSampling.time(it)) }
    p.blurScore?.let { add("선명도" to "%.0f".format(it)) }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1fMB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0fKB".format(bytes / 1024.0)
}
