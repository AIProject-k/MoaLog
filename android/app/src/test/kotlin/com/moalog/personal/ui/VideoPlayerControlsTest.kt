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

    @Test
    fun speedCyclesThroughFourStepsBackToNormal() {
        val steps = generateSequence(VideoPlayerControls()) { it.cycleSpeed() }.take(5).map { it.speed }.toList()
        assertEquals(listOf(1f, 1.5f, 2f, 0.5f, 1f), steps)
    }

    @Test
    fun speedLabelDropsTheDecimalForWholeNumbers() {
        assertEquals("1×", VideoPlayerControls.speedLabel(1f))
        assertEquals("2×", VideoPlayerControls.speedLabel(2f))
        assertEquals("1.5×", VideoPlayerControls.speedLabel(1.5f))
        assertEquals("0.5×", VideoPlayerControls.speedLabel(0.5f))
    }

    @Test
    fun speedSurvivesMovingOnToAnotherPlayback() {
        val fast = VideoPlayerControls().cycleSpeed().cycleSpeed()
        assertEquals(2f, fast.resetPlayback().speed)
    }
}
