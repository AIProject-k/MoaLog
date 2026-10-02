package com.moalog.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 화면 위에 얹히는 것들: 하단 시트 · 대화상자 · 토스트 · 팝업 메뉴.
 * 안드로이드의 ModalBottomSheet/AlertDialog를 쓰지 않고 직접 그린다 — 디자인의 모양(반지름 20 · 손잡이 ·
 * 톤 차이로만 나눈 표면)을 그대로 지키려는 것이고, 전부 앱 안 한 겹이라 뒤로가기 처리가 한 곳이다.
 */

/** 시트·대화상자 뒤 어둡게. 누르면 닫힌다. */
@Composable
private fun Scrim(alpha: Float, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)).clickable(src, indication = null) { onClose() })
}

/**
 * 하단 시트. 둥근 위쪽 모서리 20 · 손잡이 36×4 · 안쪽 여백 8/16/(12 + 내비게이션 바).
 * 내용이 길면 680dp까지 자라고 그 위는 스크롤한다.
 */
@Composable
fun BottomSheet(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 680.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val swallow = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(shown, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) { Scrim(.5f, onClose) }
        AnimatedVisibility(
            shown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(250)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                    .background(theme.surface1)
                    .clickable(swallow, indication = null) { }
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(theme.hint),
                )
                content()
            }
        }
    }
}

/** 시트 맨 위 줄: 제목 + 닫기 버튼(선택). */
@Composable
fun SheetHeader(title: String, onClose: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = fonts.sheetTitle, color = theme.text1, modifier = Modifier.weight(1f))
        if (onClose != null) Box(Modifier.padding(end = 0.dp)) {
            IconBtn(I.Close, "닫기", onClose, tint = theme.text2, size = 22.dp, modifier = Modifier.padding(end = 0.dp))
        }
    }
}

/**
 * 가운데 대화상자. 삭제처럼 되돌릴 수 없는 일만 이걸로 한 번 더 묻는다.
 * 확인은 위험 색 알약, 취소는 글자뿐이다(디자인 그대로). 문구가 무엇이 남는지부터 말한다.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val swallow = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Scrim(.6f, onCancel)
        Column(
            Modifier.padding(horizontal = 28.dp).fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sheet)).background(theme.surface2)
                .clickable(swallow, indication = null) { }
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = fonts.sheetTitle, color = theme.text1)
            Text(body, style = fonts.bodySm, color = theme.text2)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextBtn("취소", onCancel, style = fonts.itemMd, color = theme.text2)
                Pill(confirm, onConfirm, bg = theme.danger, fg = Color.White, style = fonts.itemMd, horizontal = 18.dp)
            }
        }
    }
}

/**
 * 토스트. 화면 아래에서 올라오는 한 줄 알림(+ 선택적 동작). 사라지는 시간은 호출 측이 정한다.
 * `bottom`은 지금 화면에서 가려지면 안 되는 것(탭 바·선택 바) 위로 띄우는 값이다.
 */
@Composable
fun Toast(
    text: String?,
    action: String?,
    onAction: () -> Unit,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },
        exit = fadeOut(tween(200)),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = bottom).fillMaxWidth().heightIn(min = 48.dp)
                .shadow(8.dp, RoundedCornerShape(Radius.thumb))
                .clip(RoundedCornerShape(Radius.thumb)).background(theme.toast)
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Prose(text.orEmpty(), style = fonts.bodySm, color = theme.text1, modifier = Modifier.weight(1f))
            if (action != null) TextBtn(action, onAction, color = theme.accent, style = fonts.bodySmStrong, height = 40.dp, horizontal = 10.dp)
        }
    }
}

/** 오른쪽 위에 뜨는 팝업 메뉴(앨범 상세의 ⋮). 항목 48dp + 아래 안내문. */
@Composable
fun PopupMenu(
    onClose: () -> Unit,
    items: List<MenuItem>,
    note: String,
    modifier: Modifier = Modifier,
) {
    val swallow = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize()) {
        Scrim(.35f, onClose)
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 12.dp).width(232.dp)
                .shadow(12.dp, RoundedCornerShape(Radius.menu))
                .clip(RoundedCornerShape(Radius.menu)).background(theme.surface3)
                .clickable(swallow, indication = null) { }
                .padding(top = 6.dp),
        ) {
            for (m in items) Row(
                Modifier.fillMaxWidth().height(48.dp).clickable(onClick = m.onClick).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Ic(m.icon, size = 20.dp, tint = m.color)
                Text(m.label, style = fonts.body, color = m.color)
            }
            Hair(Modifier.padding(top = 0.dp))
            Text(note, style = fonts.micro, color = theme.text3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

class MenuItem(val label: String, val icon: Int, val color: Color, val onClick: () -> Unit)
