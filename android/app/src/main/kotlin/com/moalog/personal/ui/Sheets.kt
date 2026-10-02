package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 화면 위에 뜨는 하단 시트 네 가지. 어느 것이 뜰지는 [AppViewModel.Ui.sheet]가 정하고 여기서는 그리기만 한다.
 */

@Composable
fun AlbumSheets(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    when (ui.sheet) {
        Sheet.Add -> BottomSheet(onClose = vm::closeSheet) { AddToAlbumSheet(vm, ui, uriOf) }
        Sheet.Month -> BottomSheet(onClose = vm::closeSheet) { MonthSheet(vm, ui) }
        Sheet.Axis -> BottomSheet(onClose = vm::closeSheet) { AxisSheet(vm, ui) }
        Sheet.Move -> BottomSheet(onClose = vm::closeSheet) { MoveSheet(vm, ui, uriOf) }
        Sheet.Licenses -> BottomSheet(onClose = vm::closeSheet) { LicensesSheet() }
        null -> Unit
    }
}

/**
 * "앨범에 추가" 시트. 사진을 **복사하지 않는다** — 내 앨범은 사진을 가리키는 목록일 뿐이라 앨범에 넣어도 원래
 * 자리에 그대로 있고, 앨범을 지워도 사진은 남는다. 그 사실을 적어 둔다.
 * 아무것도 고르지 않고 열면(앨범 탭의 "새 앨범") 목록 없이 이름만 받는다.
 */
@Composable
private fun ColumnScope.AddToAlbumSheet(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    val t = theme
    val n = ui.selected.size
    var name by remember { mutableStateOf("") }

    SheetHeader(if (n > 0) "${n}개를 넣을 앨범" else "새 앨범", vm::closeSheet)
    Prose(
        "사진·영상은 원래 자리에 그대로 있습니다. 앨범은 사진·영상을 가리키는 목록입니다.",
        style = fonts.note, color = t.text2, modifier = Modifier.padding(top = 0.dp),
    )
    if (n > 0 && ui.userAlbums.isNotEmpty()) Column {
        for (a in ui.userAlbums) Row(
            Modifier.fillMaxWidth().height(64.dp).clickable { vm.addSelectedToAlbum(a.id) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlainThumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.size(48.dp).clip(RoundedCornerShape(Radius.tile)), background = t.surface3)
            Column(Modifier.weight(1f)) {
                Text(a.name, style = fonts.itemMd, color = t.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(countText(a.count, a.videos), style = fonts.meta, color = t.text3)
            }
            Ic(I.Plus, size = 20.dp, tint = t.text3)
        }
    }
    Text("새 앨범 만들기", style = fonts.metaStrong, color = t.text3, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(Radius.thumb)).background(t.surface2).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = name, onValueChange = { name = it.take(20) }, singleLine = true,
                textStyle = fonts.input.copy(color = t.text1), cursorBrush = SolidColor(t.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (name.isEmpty()) Text("앨범 이름 (20자까지)", style = fonts.input, color = t.text3)
                    inner()
                },
            )
        }
        val ready = name.isNotBlank()
        Pill(
            "만들기", { vm.createAlbumFromSheet(name) }, enabled = ready,
            bg = if (ready) t.accent else t.surface3, fg = if (ready) t.onAccent else t.text3,
            style = fonts.itemMd, horizontal = 18.dp,
        )
    }
}

/** 월별 이동. 사진을 다 불러오지 않고 그 달의 시작 위치부터 다시 읽는다. */
@Composable
private fun ColumnScope.MonthSheet(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    Text("월별 이동", style = fonts.sheetTitle, color = t.text1, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Column {
        for (m in ui.months) Row(
            Modifier.fillMaxWidth().height(48.dp).clickable { vm.jumpToMonth(m) }.drawBehind {
                drawLine(t.hair.copy(alpha = .05f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(monthLabel(m.ym), style = fonts.itemMd.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = t.text1, modifier = Modifier.weight(1f))
            Text("${"%,d".format(m.count)}개", style = fonts.note, color = t.text3)
        }
    }
}

/** AI 앨범 분류(축) 고르기. */
@Composable
private fun ColumnScope.AxisSheet(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    Text("AI 앨범 분류", style = fonts.sheetTitle, color = t.text1, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Column {
        for (a in Axis.ordered) Row(
            Modifier.fillMaxWidth().height(52.dp).clickable { vm.setAxis(a); vm.closeSheet() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(a.label, style = fonts.input, color = t.text1, modifier = Modifier.weight(1f))
            if (a == ui.axis) Ic(I.Check, size = 22.dp, tint = t.accent)
        }
    }
}

/** 분류 수정에서 고른 사진·영상을 옮길 AI 앨범. 어느 쪽이든 사진·영상은 기기에 그대로 남는다. */
@Composable
private fun ColumnScope.MoveSheet(vm: AppViewModel, ui: AppViewModel.Ui, uriOf: (Long) -> String?) {
    val t = theme
    val targets = ui.albums.filter { it.kind == AlbumKind.Tag && it.key != ui.openAlbum?.key }
    Text("옮길 AI 앨범", style = fonts.sheetTitle, color = t.text1, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    Text("사진·영상은 기기에 그대로 남습니다.", style = fonts.note, color = t.text2, modifier = Modifier.padding(bottom = 8.dp))
    Column {
        for (a in targets) Row(
            Modifier.fillMaxWidth().height(60.dp).clickable { vm.moveSelected(a.title); vm.closeSheet() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlainThumb(a.coverIds.firstOrNull()?.let(uriOf), Modifier.size(44.dp).clip(RoundedCornerShape(Radius.tile)), background = t.surface3)
            Column(Modifier.weight(1f)) {
                Text(a.title, style = fonts.itemMd, color = t.text1)
                Text(countText(a.count, a.videos), style = fonts.meta, color = t.text3)
            }
            Ic(I.ChevronRight, size = 20.dp, tint = t.text3)
        }
    }
}

/** 오픈소스 라이선스. 글꼴(OFL)은 전문을 접어 두었다가 눌러서 보여 준다 — OFL은 라이선스 문구를 함께 배포하라고 요구한다. */
@Composable
private fun ColumnScope.LicensesSheet() {
    val t = theme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ofl by remember { mutableStateOf(false) }
    Text("오픈소스 라이선스", style = fonts.sheetTitle, color = t.text1, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    val items = listOf(
        "IBM Plex Sans KR" to "SIL Open Font License 1.1 · © IBM Corp.",
        "한국어 CLIP (Bingsu/clip-vit-base-patch32-ko)" to "MIT",
        "ONNX Runtime" to "MIT",
        "Jetpack Compose · AndroidX · WorkManager" to "Apache License 2.0",
        "Coil" to "Apache License 2.0",
        "Kotlin · kotlinx.coroutines" to "Apache License 2.0",
    )
    for ((name, license) in items) Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .then(if (name.startsWith("IBM")) Modifier.clickable { ofl = !ofl } else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, style = fonts.itemMd, color = t.text1)
        Text(license + if (name.startsWith("IBM")) (if (ofl) " · 전문 접기" else " · 전문 보기") else "", style = fonts.meta, color = t.text3)
    }
    if (ofl) {
        val text = remember { runCatching { ctx.assets.open("licenses/IBMPlexSansKR-OFL.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        Text(text, style = fonts.meta, color = t.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun countText(count: Int, videos: Int): String = when {
    videos == 0 -> "${count}개"
    videos == count -> "영상 ${count}개"
    else -> "${count}개 · 영상 $videos"
}
