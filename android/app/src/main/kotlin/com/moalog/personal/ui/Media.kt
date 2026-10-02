package com.moalog.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.moalog.personal.core.VideoSampling
import com.moalog.personal.data.MediaKind
import com.moalog.personal.data.PhotoCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 사진·영상 한 칸과 날짜 머리글. 갤러리·앨범 상세·검색 결과·분류 수정·확인 모드가 전부 같은 칸을 쓴다 —
 * 칸이 화면마다 다르게 생기면 사용자가 "이게 영상인가"를 매번 다시 읽어야 한다.
 */

/** 칸 위에 얹는 표시. 모드가 서로 배타적이라 하나로 묶었다. */
sealed interface CellMark {
    data object None : CellMark

    /** 선택 모드. 선택됨은 강조 테두리 + 체크, 아니면 흰 링. */
    data class Select(val on: Boolean) : CellMark

    /** 검색 결과 확인 모드. 담김은 체크만, 뺌은 어둡게 덮고 "뺌". */
    data class Review(val on: Boolean) : CellMark
}

/** 영상 길이 표기: `0:45` · `14:56` · `1:02:03`. 길이를 모르면 "동영상". */
fun durationText(ms: Long?): String =
    ms?.takeIf { it > 0 }?.let(VideoSampling::time) ?: "동영상"

/**
 * 한 칸. 정사각형으로 자르고, 영상이면 ▶ + 길이, 즐겨찾기면 하트, 선택 중이면 선택 표시를 올린다.
 *
 * @param scene 영상이면 이 시각의 프레임을 보여 준다(검색 결과 — 영상 첫 화면이 아니라 **맞은 장면**).
 * @param caption 검색 결과의 영상에 붙는 "약 0:36 외 2곳" 띠. 있으면 길이 배지 대신 이 띠를 쓴다.
 */
@Composable
fun MediaCell(
    photo: PhotoCard,
    modifier: Modifier = Modifier,
    mark: CellMark = CellMark.None,
    favorite: Boolean = false,
    scene: Boolean = false,
    caption: String? = null,
    px: Int = 360,
) {
    val t = theme
    val video = photo.mediaKind == MediaKind.VIDEO
    val sceneMs = photo.sceneTimeMs?.takeIf { video && scene }
    var failed by remember(photo.id, sceneMs) { mutableStateOf(false) }

    Box(
        modifier.aspectRatio(1f).background(t.surface2).semantics {
            contentDescription = cellLabel(photo, video, favorite, caption)
            when (mark) {
                is CellMark.Select -> stateDescription = if (mark.on) "선택됨" else "선택 안 함"
                is CellMark.Review -> stateDescription = if (mark.on) "앨범에 담김" else "뺌"
                CellMark.None -> Unit
            }
        },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(if (sceneMs != null) SceneFrame(photo.uri, sceneMs) else photo.uri)
                .size(px).crossfade(140).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onState = { if (it is AsyncImagePainter.State.Error) failed = true },
        )

        if (failed && video) {
            // 썸네일을 못 만든 영상. 빈 칸 대신 "영상이다"가 읽히게 자리표시를 둔다(1:02:03 같은 긴 영상이 실제로 겪는다).
            Box(Modifier.fillMaxSize().background(t.surface2), contentAlignment = Alignment.Center) {
                Ic(I.Video, size = 32.dp, tint = t.hint)
            }
            Text(
                durationText(photo.durationMs), style = fonts.badge, color = t.text2,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 5.dp),
            )
        } else {
            if (caption == null && (video || favorite) && mark is CellMark.None) Scrim30()
            if (video && caption == null) VideoBadge(photo.durationMs)
            if (caption != null && video) SceneBand(caption)
            if (favorite && mark is CellMark.None) Ic(
                I.HeartFilled, size = 14.dp, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 6.dp),
            )
        }

        when (mark) {
            CellMark.None -> Unit
            is CellMark.Select -> if (mark.on) {
                Box(Modifier.fillMaxSize().background(t.accent.copy(alpha = .22f)).border(3.dp, t.accent))
                CheckBadge()
            } else UncheckedRing()
            is CellMark.Review -> if (mark.on) CheckBadge() else Box(
                Modifier.fillMaxSize().background(Color(0xC70E0E10)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Ic(I.Remove, size = 24.dp, tint = t.text2)
                    Text("뺌", style = fonts.metaStrong, color = t.text2)
                }
            }
        }
    }
}

private val spokenDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)

/** 스크린 리더가 읽는 칸 설명. "사진, 2026년 9월 23일" · "동영상 0:31, 2026년 5월 3일, 즐겨찾기". 사진에는 글자가 없으니 이게 전부다. */
private fun cellLabel(photo: PhotoCard, video: Boolean, favorite: Boolean, caption: String?): String = buildString {
    append(if (video) "동영상" else "사진")
    if (video) photo.durationMs?.takeIf { it > 0 }?.let { append(' ').append(durationText(it)) }
    photo.takenAt?.let { append(", ").append(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(spokenDate)) }
    if (favorite) append(", 즐겨찾기")
    if (caption != null) append(", ").append(caption)
}

/** 아래쪽 30dp 어두운 그라데이션. 흰 배지가 밝은 사진 위에서도 읽히게 한다(디자인의 `c.scrim`). */
@Composable
private fun BoxScope.Scrim30() {
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
    )
}

@Composable
private fun BoxScope.VideoBadge(durationMs: Long?) {
    Row(
        Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Ic(I.Play, size = 12.dp, tint = Color.White)
        Text(durationText(durationMs), style = fonts.badge, color = Color.White)
    }
}

/** 검색 결과 영상 칸: 오른쪽 위 영상 아이콘 + 아래 불투명 띠에 맞은 장면 시각. */
@Composable
private fun BoxScope.SceneBand(caption: String) {
    Ic(I.Video, size = 16.dp, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).background(Color(0xB8000000)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Ic(I.Scene, size = 11.dp, tint = Color.White)
        Text(caption, style = fonts.badge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoxScope.CheckBadge() {
    Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(theme.accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Ic(I.Check, size = 15.dp, tint = theme.onAccent) }
}

@Composable
private fun BoxScope.UncheckedRing() {
    Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
            .background(Color(0x40000000), CircleShape).border(2.dp, Color.White, CircleShape),
    )
}

/** 표지·목록용 단순 썸네일. 표시(배지·선택)가 없다. */
@Composable
fun PlainThumb(
    uri: String?,
    modifier: Modifier = Modifier,
    px: Int = 320,
    background: Color = theme.surface2,
) {
    Box(modifier.background(background)) {
        if (uri != null) AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(uri).size(px).crossfade(140).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 날짜 머리글. 높이 40, 글자는 아래쪽에 붙는다. 영상이 섞이면 "사진 2장 · 영상 1개"를 보조 색으로 덧붙인다.
 */
@Composable
fun DayHeader(label: String, meta: String, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    Row(
        modifier.fillMaxWidth().height(height).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = fonts.label.copy(lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = theme.text1, maxLines = 1)
        Text(meta, style = fonts.meta, color = theme.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
