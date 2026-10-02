package com.moalog.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 디자인의 반복되는 조각들. `design/AI Gallery Auto-Sort.dc.html`에 한 번씩 나오는
 * 스타일을 여기 모아 둔다 — 화면 코드에 색과 반지름을 다시 쓰지 않기 위함이다.
 *
 * 디자인의 `style-hover`는 Compose에서 pressed로 옮긴다. 폰에는 hover가 없다.
 */

/** 주 버튼. 디자인: h54 · r27 · accent · 16/700 */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = theme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (pressed) t.accentHi else t.accent)
            .clickable(src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = fonts.button, color = Color.White)
    }
}

/** 부 버튼. 디자인: h48 · 투명 · 14 · 텍스트 .5 */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = theme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.fillMaxWidth().height(48.dp).clickable(src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = fonts.body, color = if (pressed) t.text else t.textMuted)
    }
}

/** 카드 안 한 줄 짜리 안내. 디자인: r14 · #17171B · 점 + 텍스트 */
@Composable
fun BulletCard(text: String) {
    val t = theme
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.chip))
            .background(t.surface2)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(t.accent))
        Text(text, style = fonts.caption, color = t.textBody)
    }
}

/** 섹션 라벨. 디자인: 12/700/.1em · 텍스트 .35 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = fonts.sectionLabel, color = theme.textFaint, modifier = modifier)

/** 칩. `selected`면 accent 배경 + 흰 글씨 (디자인의 axes 스타일). */
@Composable
fun Chip(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val t = theme
    val bg = when {
        selected -> t.accent
        tinted -> t.accentTint(.10f)
        else -> t.surface3
    }
    val fg = when {
        selected -> Color.White
        tinted -> t.accentHi
        else -> t.text.copy(alpha = .6f)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.chip))
            .background(bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(label, style = fonts.chip.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null), color = fg)
    }
}

/** 뒤로가기 원형 버튼. 디자인: 34dp · r17 · #1A1A20 */
@Composable
fun BackButton(onClick: () -> Unit) {
    val t = theme
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(t.surface3).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("←", style = fonts.body.copy(fontSize = 16.sp), color = t.text.copy(alpha = .8f))
    }
}

/**
 * 토스트. 디자인: 밝은 배경(#F2F0ED) + 어두운 글씨, 아래에서 올라옴.
 * 화면 위에 떠 있고 2.6초 뒤 사라진다 — 시간 관리는 호출 측(AppState)이 한다.
 */
@Composable
fun Toast(message: String?, modifier: Modifier = Modifier) {
    val t = theme
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(tween(220)) { it },
        modifier = modifier,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(Radius.chip))
                .background(t.text)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                message.orEmpty(),
                style = fonts.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = t.bg,
            )
        }
    }
}

/** 하단 탭. 디자인: 아이콘 자리는 20dp 라운드 사각형(실제 아이콘은 미정) */
@Composable
fun BottomNav(current: Screen, onGo: (Screen) -> Unit) {
    val t = theme
    Row(
        Modifier.fillMaxWidth()
            .background(t.bg.copy(alpha = .92f))
            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
    ) {
        for (s in Screen.tabs) {
            val on = current == s
            Column(
                Modifier.weight(1f).heightIn(min = 44.dp).clickable { onGo(s) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) t.accent else t.text.copy(alpha = .28f))
                )
                Text(
                    s.tabLabel,
                    style = fonts.badge.copy(letterSpacing = 0.sp),
                    color = if (on) t.text else t.textFaint,
                )
            }
        }
    }
}

/**
 * 온보딩의 맥동하는 3×3 그리드. 디자인의 `scanpulse` 애니메이션 + 고정 색 9개.
 * "AI가 사진을 훑는다"를 그림 하나로 말하는 자리라 색을 그대로 옮겼다.
 */
@Composable
fun ScanningGrid(modifier: Modifier = Modifier) {
    val t = theme
    val pulse = rememberInfiniteTransition(label = "scanpulse")
    val scale by pulse.animateFloat(
        initialValue = .92f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "scale",
    )
    val alpha by pulse.animateFloat(
        initialValue = .35f, targetValue = .80f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().scale(scale).clip(CircleShape)
                .background(Brush.radialGradient(
                    listOf(t.accent.copy(alpha = .28f * (alpha / .8f)), Color.Transparent),
                    radius = 210f,
                ))
        )
        Column(
            Modifier.padding(26.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (row in TILE_COLORS.chunked(3)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (c in row) {
                        val col = if (c == ACCENT_SLOT) t.accent else Color(c)
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Radius.tile)).background(col))
                    }
                }
            }
        }
    }
}

/** 디자인이 지정한 9칸 색. 가운데 왼쪽 한 칸만 악센트다. */
private const val ACCENT_SLOT = 0x00000001L
private val TILE_COLORS = listOf(
    0xFF2B2B33, 0xFF3A2A28, 0xFF242430,
    ACCENT_SLOT, 0xFF2F2F38, 0xFF3A3A44,
    0xFF26262E, 0xFF4A3330, 0xFF2B2B33,
)

/**
 * "앨범에 추가" 시트. 사진을 **복사하지 않는다** — 내 앨범은 사진을 가리키는 목록일 뿐이라
 * 앨범에 넣어도 원래 자리에 그대로 있고, 앨범을 지워도 사진은 남는다. 그 사실을 적어 둔다.
 */
@Composable
fun AlbumPicker(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    var name by remember { mutableStateOf("") }
    val scrim = remember { MutableInteractionSource() }
    val sheet = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
            .clickable(scrim, indication = null) { vm.closePicker() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(t.surface2)
                .clickable(sheet, indication = null) { }
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("${ui.selected.size}장을 넣을 앨범", style = fonts.header, color = t.text)
            Text(
                "사진은 원래 자리에 그대로 있습니다. 앨범은 사진을 가리키는 목록입니다.",
                style = fonts.meta, color = t.textMuted,
            )

            for (a in ui.userAlbums) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).background(t.surface3)
                    .clickable { vm.addSelectedToAlbum(a.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(a.name, style = fonts.bodySm, color = t.text, modifier = Modifier.weight(1f))
                Text("${a.count}장", style = fonts.meta, color = t.textFaint)
            }

            SectionLabel("새 앨범 만들기")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(Radius.field))
                        .background(t.surface3).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        textStyle = fonts.body.copy(color = t.text),
                        cursorBrush = SolidColor(t.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (name.isEmpty())
                                Text("앨범 이름", style = fonts.body, color = t.text.copy(alpha = .38f))
                            inner()
                        },
                    )
                }
                Box(
                    Modifier.height(46.dp).clip(RoundedCornerShape(23.dp))
                        .background(if (name.isBlank()) t.surface4 else t.accent)
                        .then(if (name.isBlank()) Modifier
                        else Modifier.clickable { vm.createAlbumWithSelected(name) })
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("만들기", style = fonts.caption.copy(fontWeight = FontWeight.Bold),
                        color = if (name.isBlank()) t.textFaint else Color.White)
                }
            }
            GhostButton("닫기") { vm.closePicker() }
        }
    }
}
