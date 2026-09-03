package kr.slz.photolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.slz.photolog.core.TimeSource
import kr.slz.photolog.data.PhotoDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 사진 상세. 디자인의 "AI가 읽은 정보" 표를 **실제로 아는 것만으로** 채운다.
 *
 * 디자인 원문에는 얼굴 이름("예린, 미확인 1명")과 OCR 결과가 있는데 둘 다 아직 없는
 * 기능이다(§8.4·§10.3). 없는 줄을 "인식된 글자 없음"처럼 써 두면 OCR을 돌려 본 것처럼
 * 읽히므로, 아는 항목만 넣고 나머지는 줄을 빼 버린다.
 */
@Composable
fun PhotoScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val p = ui.openPhoto ?: return
    val zone = ZoneId.systemDefault()

    LazyColumn(Modifier.fillMaxSize().background(t.bgDeep)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BackButton { vm.back() }
                Text(dateLine(p, zone), style = fonts.caption, color = t.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            // 3:4는 디자인 값이다. 실제 비율과 다르면 Crop이 잘라내지만, 상세에서
            // 전체를 보고 싶은 경우가 있어 원본 비율을 쓴다.
            val ratio = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 3f / 4f
            Thumb(p.uri, Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2f)), px = 1080)
        }
        item {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("AI가 읽은 정보")
                    for ((k, v) in facts(p, zone)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(k, style = fonts.chip, color = t.text.copy(alpha = .42f),
                                modifier = Modifier.width(74.dp))
                            Text(v, style = fonts.bodySm, color = t.textStrong, modifier = Modifier.weight(1f))
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(t.divider))
                    }
                }

                val tags = p.tags.filter { it.source != "rule" }
                if (tags.isNotEmpty()) FlowChips(tags.map { "#${it.label}" })

                // 1순위 CLIP 태그가 있을 때만 확인을 묻는다. 물어볼 게 없으면 카드를 뺀다.
                p.tags.firstOrNull { it.source == "clip" }?.let { top ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card))
                            .background(t.surface).padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "이 사진은 '${top.label}' 앨범에 들어갔습니다. 맞나요?",
                            style = fonts.bodySm, color = t.text.copy(alpha = .8f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("맞아요", Modifier.weight(1f), t.surface5) {
                                vm.confirmTag(p.id, top.label)
                            }
                            SmallButton("다른 앨범으로", Modifier.weight(1f), t.accent, Color.White) {
                                vm.openFix()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dateLine(p: PhotoDetail, zone: ZoneId): String {
    val t = p.takenAt ?: return "날짜 미상"
    val d = Instant.ofEpochSecond(t).atZone(zone)
    val s = d.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 a h:mm"))
    // 촬영시각인지 파일시각인지 밝힌다 — 카톡 저장 사진은 시각이 촬영시점이 아니다
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
    p.cameraModel?.let { add("카메라" to it) }
    if (p.lat != null && p.lon != null) add("위치" to "%.5f, %.5f".format(p.lat, p.lon))
    if (p.width > 0) add("크기" to "${p.width}×${p.height} · ${p.bytes / 1024}KB")
    p.blurScore?.let { add("선명도" to "%.0f".format(it)) }
}

@Composable
private fun FlowChips(labels: List<String>) {
    // Compose 1.6의 FlowRow는 실험 API라 안 쓴다. 칩이 많지 않아 LazyRow로 충분하다.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(labels) { l ->
            Box(
                Modifier.clip(RoundedCornerShape(Radius.chip)).background(theme.surface2)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) { Text(l, style = fonts.chip, color = theme.text.copy(alpha = .7f)) }
        }
    }
}

@Composable
private fun SmallButton(
    text: String, modifier: Modifier, bg: Color,
    fg: Color = theme.text, onClick: () -> Unit,
) = Box(
    modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(bg).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(text, style = fonts.caption.copy(fontWeight = FontWeight.Bold), color = fg) }

// ---------------------------------------------------------------- FIX

/**
 * 분류 수정. 잘못 묶인 사진을 골라 다른 앨범으로 옮긴다.
 *
 * 디자인의 "다시 학습" 버튼은 **학습하지 않는다** — 확정된 사진의 임베딩을 카테고리
 * 프로토타입에 섞는 방식이라(§8.5) 즉시 반영되고 되돌릴 수 있다. 라벨은 디자인 문구를
 * 쓰되 토스트에서 무엇이 일어났는지 정확히 말한다.
 */
@Composable
fun FixScreen(vm: AppViewModel, ui: AppViewModel.Ui, columns: Int = 3) {
    val t = theme
    val n = ui.selected.size
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton { vm.back() }
            Text("분류 수정", style = fonts.header, color = t.text, modifier = Modifier.weight(1f))
            Text(if (n > 0) "${n}장 선택" else "사진을 고르세요",
                style = fonts.meta, color = t.text.copy(alpha = .42f))
        }
        Text(
            "잘못 묶인 사진을 고르면 다른 앨범으로 옮기고, 같은 패턴을 다음 분석에 반영합니다.",
            style = fonts.chip.copy(lineHeight = androidx.compose.ui.unit.TextUnit(21.25f,
                androidx.compose.ui.unit.TextUnitType.Sp)),
            color = t.textMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            items(ui.fixPhotos.chunked(columns)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (p in row) {
                        val on = p.id in ui.selected
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clickable { vm.toggleSelect(p.id) }
                        ) {
                            Thumb(p.uri, Modifier.matchParentSize())
                            if (on) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)))
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (on) t.accent else Color.Black.copy(alpha = .25f)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Text("✓", style = fonts.badge, color = Color.White) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(t.bg)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(vm.moveTargets()) { label ->
                    Box(
                        Modifier.clip(RoundedCornerShape(Radius.card)).background(t.surface4)
                            .clickable { vm.moveSelected(label) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) { Text(label, style = fonts.chip, color = t.text.copy(alpha = .8f)) }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
                    .background(if (n > 0) t.accent else t.surface4)
                    .then(if (n > 0) Modifier.clickable { vm.applyFeedback() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (n > 0) "${n}장 이 앨범에서 제외" else "고른 사진 없음",
                    style = fonts.itemTitle,
                    color = if (n > 0) Color.White else t.textFaint,
                )
            }
        }
    }
}
