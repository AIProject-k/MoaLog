package com.moalog.personal.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * 영상 한 개의 재생기. `VideoView`를 감싸고, **재생 상태를 화면이 읽을 수 있게** 내놓는다.
 *
 * 예전에는 시스템 기본 `MediaController`를 썼다. 디자인이 그걸 앱 톤의 컨트롤(되감기 5초·재생·빨리 감기 15초 +
 * 탐색 막대)로 바꿨으므로, 컨트롤은 [VideoControls]·[SeekBar]가 그리고 이 객체는 위치·길이·재생 여부만 쥔다.
 * 로컬 재생 전용이다 — 뷰어를 떠나면 멈추고, 앱이 백그라운드로 가면 일시정지한다.
 */
@Stable
class PlayerHandle(val view: VideoView) {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var prepared by mutableStateOf(false)
    var failed by mutableStateOf(false)
    internal var player: MediaPlayer? = null

    fun play() {
        if (!prepared) return
        if (durationMs > 0 && positionMs >= durationMs - 200) { view.seekTo(0); positionMs = 0 }
        view.start(); playing = true
    }

    /**
     * 지금 자리에서 그대로 튼다. [play]는 끝에 가까우면 처음으로 돌아가는데, 장면 칩이 영상의 마지막 프레임("약 0:19")으로
     * 일부러 건너뛴 경우에는 그러면 안 된다(S23 실기기에서 마지막 장면 칩을 누르면 처음부터 재생되던 문제).
     */
    fun playHere() {
        if (!prepared) return
        view.start(); playing = true
    }

    fun pause() { if (prepared) view.pause(); playing = false }

    fun toggle() = if (playing) pause() else play()

    fun seekTo(ms: Long) {
        if (!prepared) return
        val t = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        view.seekTo(t.toInt()); positionMs = t
    }

    /** 되감기·빨리 감기. 디자인의 −5초 / +15초. */
    fun skip(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /**
     * 재생 속도. `setPlaybackParams`는 멈춘 플레이어에 불리면 그 자리에서 재생을 시작하므로, 원래 멈춰 있었으면 다시 멈춘다.
     * 기기가 그 속도를 지원하지 않으면 예외가 나는데, 그때는 조용히 1배로 둔다.
     */
    internal fun applySpeed(speed: Float) {
        val mp = player ?: return
        runCatching {
            val wasPlaying = mp.isPlaying
            runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }
                .onFailure { mp.playbackParams = PlaybackParams().setSpeed(1f) }
            if (!wasPlaying && mp.isPlaying) mp.pause()
        }
    }
}

@Composable
fun rememberPlayer(
    mediaId: Long,
    uri: String,
    startMs: Long,
    controls: VideoPlayerControls,
    onPlaybackChanged: (Long, Long, Boolean) -> Unit,
    onPositionChanged: (Long, Long) -> Unit,
): PlayerHandle {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentControls by rememberUpdatedState(controls)
    val currentOnPlaybackChanged by rememberUpdatedState(onPlaybackChanged)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val handle = remember(uri) {
        val view = VideoView(context)
        PlayerHandle(view).also { h ->
            view.setOnPreparedListener { mp ->
                h.player = mp
                mp.setVolume(if (currentControls.muted) 0f else 1f, if (currentControls.muted) 0f else 1f)
                h.durationMs = view.duration.toLong().coerceAtLeast(0L)
                val position = currentControls.positionMs.takeIf { it > 0L } ?: startMs
                if (position > 0) view.seekTo(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                h.positionMs = position
                h.prepared = true
                if (currentControls.resumePlayback) { view.start(); h.playing = true }
                else view.seekTo(position.toInt().coerceAtLeast(1))        // 멈춘 채로 열려도 그 자리의 프레임이 보이게
            }
            view.setOnCompletionListener { h.playing = false; h.positionMs = h.durationMs }
            view.setOnErrorListener { _, _, _ -> h.failed = true; true }
            view.setVideoURI(Uri.parse(uri))
        }
    }

    // 위치·재생 여부를 주기적으로 읽는다. 컨트롤 막대가 이 값을 그린다.
    LaunchedEffect(handle) {
        while (true) {
            delay(200)
            if (handle.prepared && !handle.failed) {
                handle.positionMs = handle.view.currentPosition.toLong()
                handle.playing = handle.view.isPlaying
            }
        }
    }
    // 다른 장면 칩을 누르면 준비된 플레이어에서 그 자리로 옮겨 바로 튼다.
    LaunchedEffect(controls.seekToken, handle.prepared) {
        if (handle.prepared && controls.seekMs >= 0) { handle.seekTo(controls.seekMs); handle.playHere() }
    }
    LaunchedEffect(controls.muted, handle.prepared) {
        handle.player?.setVolume(if (controls.muted) 0f else 1f, if (controls.muted) 0f else 1f)
    }
    // 준비가 끝난 뒤에도 속도를 맞춘다. 영상을 넘기면 플레이어가 새로 만들어지므로 여기서 다시 건다.
    LaunchedEffect(controls.speed, handle.prepared) {
        if (handle.prepared) handle.applySpeed(controls.speed)
    }
    DisposableEffect(handle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    currentOnPlaybackChanged(mediaId, handle.view.currentPosition.toLong(), handle.view.isPlaying)
                    handle.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (currentControls.resumePlayback) handle.play()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            currentOnPositionChanged(mediaId, handle.view.currentPosition.toLong())
            owner.lifecycle.removeObserver(observer)
            handle.view.stopPlayback()
        }
    }
    return handle
}

/**
 * 전체 화면이면 가로로 돌리고 시스템 바를 숨긴다. 나가거나 뷰어를 떠나면 되돌린다.
 *
 * **전체 화면에서 나올 때는 세로로 먼저 돌려놓는다.** 센서에 맡기기만 하면 폰이 책상에 놓여 있거나 비스듬할 때
 * 센서가 방향을 정하지 못해 가로로 남는다(S23 실기기에서 확인). 세로로 맞춘 뒤 잠시 있다가 센서에 돌려준다.
 */
@Composable
fun FullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current as? Activity
    var wasFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(activity, fullscreen) {
        val leaving = wasFullscreen && !fullscreen
        wasFullscreen = fullscreen
        activity?.requestedOrientation = when {
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            leaving -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity?.window?.let { window ->
            val insets = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) {
                insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(WindowInsetsCompat.Type.systemBars())
            } else insets.show(WindowInsetsCompat.Type.systemBars())
        }
        if (leaving) {
            kotlinx.coroutines.delay(1_500)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/**
 * 영상이 그려지는 면. 비율은 `VideoView`가 맞춰 가운데에 놓는다.
 * 같은 `VideoView`가 페이저 한 장과 전체 화면 사이를 오가므로, 붙이기 전에 이전 부모에서 떼어 낸다
 * (뷰는 부모가 둘일 수 없다).
 */
@Composable
fun VideoSurface(player: PlayerHandle, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> android.widget.FrameLayout(ctx) },
        modifier = modifier,
        update = { frame ->
            val v = player.view
            if (v.parent !== frame) {
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                // 영상은 비율대로 줄어든 크기로 그려지므로 가운데에 놓아야 띠 한가운데에 앉는다.
                frame.addView(v, android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER))
            }
        },
        onRelease = { frame -> frame.removeAllViews() },
    )
}

/**
 * 탐색 막대. 높이 32(터치 영역), 가운데 4dp 트랙 + 강조색 진행 + 14dp 흰 손잡이. 장면 마커는 막대 **위**에
 * 8×8 마름모(디자인이 제안으로 남긴 것)로 찍는다.
 *
 * 끄는 동안은 막대만 움직이고(미리보기), 놓을 때 한 번만 [onSeek]한다 — 끄는 내내 `seekTo`를 부르면 디코더가 못 따라온다.
 */
@Composable
fun SeekBar(
    fraction: Float,
    markers: List<Float>,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: androidx.compose.ui.graphics.Color = theme.track,
) {
    val t = theme
    var width by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val density = LocalDensity.current

    Box(
        modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun frac(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    dragging = frac(down.position.x)
                    while (true) {
                        val event = awaitPointerEventAny()
                        val c = event.changes.first()
                        if (!c.pressed) { dragging?.let(onSeek); dragging = null; break }
                        dragging = frac(c.position.x); c.consume()
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).offset(y = 14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(trackColor))
        Box(Modifier.fillMaxWidth(shown).height(4.dp).offset(y = 14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(t.accent))
        for (m in markers) Box(
            Modifier.offset(x = with(density) { (width * m).toDp() } - 4.dp, y = 3.dp).size(8.dp).rotate(45f).background(t.accent),
        )
        Box(
            Modifier.offset(x = with(density) { (width * shown).toDp() } - 7.dp, y = 9.dp).size(14.dp)
                .clip(CircleShape).background(androidx.compose.ui.graphics.Color.White),
        )
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPointerEventAny() =
    awaitPointerEvent(PointerEventPass.Main)
