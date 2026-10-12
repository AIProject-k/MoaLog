package com.moalog.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 디자인(`MoaLog App.dc.html`)의 반복 조각들. 값은 템플릿의 인라인 스타일에서 그대로 옮겼다 —
 * 화면 코드에 색·반지름·글자 크기를 다시 쓰지 않으려고 여기 모아 둔다.
 */

/** 한 줄짜리 눌림 표시. 디자인은 hover/눌림을 `surface3`로 바꾸지만 폰에는 기본 물결이 맞다. */
fun Modifier.tap(onClick: () -> Unit, label: String? = null, role: Role = Role.Button): Modifier =
    this.semantics { if (label != null) contentDescription = label; this.role = role }.clickable(onClick = onClick)

// ---------------------------------------------------------------- 버튼

/** 48×48 아이콘 버튼. 터치 영역을 48dp로 지킨다(디자인의 모든 상단 아이콘). */
@Composable
fun IconBtn(
    id: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = theme.text1,
    size: Dp = 24.dp,
) {
    Box(
        modifier.size(48.dp).tap(onClick, label),
        contentAlignment = Alignment.Center,
    ) { Ic(id, size = size, tint = tint) }
}

/** 채움 알약 버튼. 주 동작은 accent, 보조는 surface3, 삭제는 danger. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = theme.accent,
    fg: Color = theme.onAccent,
    height: Dp = 48.dp,
    icon: Int? = null,
    style: TextStyle = fonts.button,
    horizontal: Dp = 20.dp,
    enabled: Boolean = true,
) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(bg)
            .let { if (enabled) it.tap(onClick) else it }
            .padding(horizontal = horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Ic(icon, size = 20.dp, tint = fg)
        Text(text, style = style, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 글자만 있는 버튼(취소·나중에 할게요·이어서). */
@Composable
fun TextBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = theme.text2,
    style: TextStyle = fonts.itemMd,
    height: Dp = 48.dp,
    horizontal: Dp = 16.dp,
) {
    Box(
        modifier.height(height).tap(onClick).padding(horizontal = horizontal),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = style, color = color, maxLines = 1) }
}

// ---------------------------------------------------------------- 사진·동영상 세그먼트

private val MEDIA_OPTIONS = listOf("all" to "전체", "image" to "사진", "video" to "동영상")

/**
 * 전체 / 사진 / 동영상. 갤러리·앨범·앨범 상세가 **같은 것**을 쓴다.
 * 동영상이 골라지면 강조색으로 채워 지금 보기가 멀리서도 읽힌다(디자인 결정 5).
 *
 * @param fill 앨범 탭처럼 전체 폭을 3등분하고 아이콘을 붙인다. 아니면 내용 폭의 작은 세그먼트다.
 */
@Composable
fun MediaSegment(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    Row(
        modifier.let { if (fill) it.fillMaxWidth() else it }
            .clip(RoundedCornerShape(Radius.thumb)).background(theme.surface2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((kind, label) in MEDIA_OPTIONS) {
            val on = kind == selected
            val accent = on && kind == "video"
            val bg by animateColorAsState(
                when { accent -> theme.accent; on -> theme.track; else -> Color.Transparent },
                tween(200), label = "seg",
            )
            val fg = when { accent -> theme.onAccent; on -> theme.text1; else -> theme.text2 }
            Row(
                Modifier.let { if (fill) it.weight(1f) else it }
                    .height(42.dp).clip(RoundedCornerShape(Radius.segInner)).background(bg)
                    .semantics { role = Role.Tab; contentDescription = label }
                    .clickable { onSelect(kind) }
                    .padding(horizontal = if (fill) 0.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, if (fill) Alignment.CenterHorizontally else Alignment.Start),
            ) {
                if (fill && kind != "all") Ic(if (kind == "video") I.Video else I.Photo, size = 18.dp, tint = fg)
                Text(
                    label,
                    style = (if (fill) fonts.label else fonts.chipRegular).copy(
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Normal,
                        fontSize = if (fill) 14.sp else 13.sp,
                    ),
                    color = fg,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 하단 탭

/**
 * 하단 탭 4개. 활성 탭은 아이콘 뒤에 56×28 알약이 깔린다. 정리 탭에는 **겹치는 항목이 있고 지금 그 탭이
 * 아닐 때만** 점이 붙는다(디자인 결정 3). 높이 84 = 내용 64 + 제스처 영역 20 — 실제 기기에서는 시스템
 * 내비게이션 바 높이만큼만 더한다.
 */
@Composable
fun TabBar(current: Screen, cleanupDot: Boolean, onGo: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val hair = theme.hair
    Column(
        modifier.fillMaxWidth().background(theme.tabBar)
            .drawBehind { drawLine(hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            for (s in Screen.tabs) {
                val on = current == s
                val pill by animateColorAsState(if (on) theme.surface3 else Color.Transparent, tween(200), label = "pill")
                val fg = if (on) theme.text1 else theme.text3
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .semantics { role = Role.Tab; contentDescription = s.tabLabel }
                        .clickable { onGo(s) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.width(56.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Ic(tabIcon(s), size = 24.dp, tint = fg)
                        if (s == Screen.Cleanup && cleanupDot && !on) Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 14.dp)
                                .size(7.dp).clip(CircleShape).background(theme.accent),
                        )
                    }
                    Text(s.tabLabel, style = fonts.badge, color = fg)
                }
            }
        }
        Box(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

private fun tabIcon(s: Screen): Int = when (s) {
    Screen.Photos -> I.Gallery
    Screen.Albums -> I.Album
    Screen.Map -> I.Pin
    Screen.Search -> I.Search
    else -> I.Clean
}

// ---------------------------------------------------------------- 상단 바

/** 상태 표시줄 아래 한 줄. 높이는 디자인의 56(제목 화면) / 64(상세 화면). */
@Composable
fun TopRow(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    background: Color = Color.Transparent,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().background(background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** 제목 + 한 줄 부제. 말줄임으로 자르고 부제는 보조 색이다. */
@Composable
fun TitleBlock(
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = fonts.title,
    subIcon: Int? = null,
    center: Boolean = false,
) {
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(title, style = titleStyle, color = theme.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (subIcon != null) Ic(subIcon, size = 13.dp, tint = theme.text2)
            Text(sub, style = fonts.meta, color = theme.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- 상태 화면

/** 빈 상태. 64dp 원 + 제목 + 설명(선택). `top`은 디자인이 화면마다 다르게 둔 위쪽 여백이다. */
@Composable
fun EmptyState(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    top: Dp = 96.dp,
    iconTint: Color = theme.text3,
    titleStyle: TextStyle = fonts.item,
    titleColor: Color = theme.text1,
) {
    Column(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = top),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(theme.surface1), contentAlignment = Alignment.Center) {
            Ic(icon, size = 28.dp, tint = iconTint)
        }
        Prose(title, style = titleStyle, color = titleColor, textAlign = TextAlign.Center)
        if (description != null) Prose(description, style = fonts.caption, color = theme.text2, textAlign = TextAlign.Center)
    }
}

/** 둥글게 도는 28dp 로딩 표시. 디자인의 `moaspin .8s`. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 28.dp, stroke: Dp = 3.dp) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "angle",
    )
    val track = theme.surface3
    val head = theme.accent
    Box(
        modifier.size(size).rotate(angle).drawBehind {
            val s = stroke.toPx()
            val inset = s / 2
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            drawArc(head, -90f, 90f, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Butt))
        },
    )
}

/** 점선 윤곽 칩 모양에 쓰는 점선 테두리. */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = this.drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
    )
}

/**
 * 스위치 그림. 48×28, 켜짐은 강조색 트랙 + 어두운 손잡이. 디자인의 설정 토글.
 * 누르는 일과 "켜짐/꺼짐"을 스크린 리더에 알리는 일은 이걸 품은 줄이 `toggleable`로 맡는다 — 이름 없는 스위치가 따로 잡히지 않게.
 */
@Composable
fun MoaSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) theme.accent else theme.track, tween(150), label = "track")
    val knob by animateColorAsState(if (checked) theme.onAccent else theme.text2, tween(150), label = "knob")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, tween(150), label = "x")
    Box(modifier.width(48.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
        Box(Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape).background(knob))
    }
}

/** 구분선(1dp, rgba(255,255,255,.07)). */
@Composable
fun Hair(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(theme.hair))
}

/** 화면 맨 위에서 상태 표시줄 높이만큼 비우는 빈 칸. 시스템 바 뒤까지 그리는 화면이 쓴다. */
@Composable
fun StatusBarSpacer() {
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
}
