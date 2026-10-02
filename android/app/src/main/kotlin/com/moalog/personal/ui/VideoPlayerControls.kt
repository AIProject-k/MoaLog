package com.moalog.personal.ui

/**
 * 영상 재생 상태. 화면이 다시 그려져도(회전·전체 화면) 위치와 재생 여부를 잃지 않도록
 * 뷰모델이 들고 있는다.
 *
 * `seekToken`은 "같은 시각으로 다시 가기"도 새 요청으로 치기 위한 번호다 — 시각만 보면
 * 0:12 장면을 두 번 눌렀을 때 두 번째는 무시된다.
 */
data class VideoPlayerControls(
    val muted: Boolean = false,
    val fullscreen: Boolean = false,
    val positionMs: Long = 0L,
    val resumePlayback: Boolean = false,
    val seekMs: Long = -1L,
    val seekToken: Int = 0,
) {
    fun toggleMute() = copy(muted = !muted)
    fun toggleFullscreen() = copy(fullscreen = !fullscreen)
    fun exitFullscreen() = copy(fullscreen = false)
    fun savePlayback(positionMs: Long, resumePlayback: Boolean) =
        copy(positionMs = positionMs.coerceAtLeast(0L), resumePlayback = resumePlayback)
    fun resetPlayback(autoPlay: Boolean = false) = copy(positionMs = 0L, resumePlayback = autoPlay, seekMs = -1L)
    fun seek(ms: Long) = copy(seekMs = ms.coerceAtLeast(0L), seekToken = seekToken + 1, resumePlayback = true)
}
