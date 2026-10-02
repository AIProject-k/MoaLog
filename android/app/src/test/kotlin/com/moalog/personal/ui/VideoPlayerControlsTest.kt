package com.moalog.personal.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPlayerControlsTest {
    @Test
    fun muteAndFullscreenTogglesKeepEachOthersState() {
        val muted = VideoPlayerControls().toggleMute()
        assertTrue(muted.muted)
        assertFalse(muted.fullscreen)

        val fullscreen = muted.toggleFullscreen()
        assertTrue(fullscreen.muted)
        assertTrue(fullscreen.fullscreen)
        assertFalse(fullscreen.exitFullscreen().fullscreen)
    }

    @Test
    fun playbackPositionAndPlayingStateCanBeSavedAndReset() {
        val saved = VideoPlayerControls().savePlayback(positionMs = 4_800L, resumePlayback = true)

        assertEquals(4_800L, saved.positionMs)
        assertTrue(saved.resumePlayback)
        assertEquals(VideoPlayerControls(), saved.resetPlayback())
    }
}
