package com.moalog.personal.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moalog.personal.R

/**
 * 디자인의 아이콘 세트. 24dp 격자, 스트로크 1.75, round 끝·모서리의 벡터 40개를 한 이름 아래 모았다.
 * 색은 전부 흰색으로 그려 두고 [Ic]가 tint로 칠한다 — 디자인의 `currentColor`와 같다.
 *
 * 원본: `MoaLog App.dc.html`의 `<symbol id="i-…">`. 바꿀 때는 원본에서 다시 뽑는다.
 */
object I {
    val Gallery = R.drawable.ic_gallery
    val Album = R.drawable.ic_album
    val Search = R.drawable.ic_search
    val Clean = R.drawable.ic_clean
    val Settings = R.drawable.ic_settings
    val Back = R.drawable.ic_back
    val Close = R.drawable.ic_close
    val More = R.drawable.ic_more
    val Share = R.drawable.ic_share
    val Trash = R.drawable.ic_trash
    val Heart = R.drawable.ic_heart
    val HeartFilled = R.drawable.ic_heart_f
    val Info = R.drawable.ic_info
    val Check = R.drawable.ic_check
    val Play = R.drawable.ic_play
    val Pause = R.drawable.ic_pause
    val Volume = R.drawable.ic_vol
    val Mute = R.drawable.ic_mute
    val Fullscreen = R.drawable.ic_fs
    val FullscreenExit = R.drawable.ic_fs_exit
    val Video = R.drawable.ic_video
    val Photo = R.drawable.ic_photo
    val Mine = R.drawable.ic_mine
    val Folder = R.drawable.ic_folder
    val Ai = R.drawable.ic_ai
    val Record = R.drawable.ic_record
    val Filter = R.drawable.ic_filter
    val Plus = R.drawable.ic_plus
    val Device = R.drawable.ic_device
    val Offline = R.drawable.ic_offline
    val Warn = R.drawable.ic_warn
    val Undo = R.drawable.ic_undo
    val Refresh = R.drawable.ic_refresh
    val Scene = R.drawable.ic_scene
    val Remove = R.drawable.ic_remove
    val ChevronDown = R.drawable.ic_chev_d
    val ChevronRight = R.drawable.ic_chev_r
    val Rewind = R.drawable.ic_rw
    val Forward = R.drawable.ic_ff
    val Pin = R.drawable.ic_pin
}

/** 아이콘 한 개. `description`이 있으면 스크린 리더가 읽는다(버튼 안에서는 버튼이 읽으므로 비워 둔다). */
@Composable
fun Ic(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = theme.text1,
    description: String? = null,
) {
    Icon(painterResource(id), description, modifier.size(size), tint)
}
