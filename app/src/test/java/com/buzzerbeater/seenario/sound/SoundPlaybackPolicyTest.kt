package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.sound.SoundPlaybackPolicy.ModeChangeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPlaybackPolicyTest {

    @Test
    fun effectiveVolume_capsAtHalf() {
        assertEquals(0.5f, SoundPlaybackPolicy.effectiveVolume(0.9), 0.0001f)
        assertEquals(0.5f, SoundPlaybackPolicy.effectiveVolume(1.0), 0.0001f)
        assertEquals(0.5f, SoundPlaybackPolicy.effectiveVolume(0.5), 0.0001f)
        assertEquals(0.3f, SoundPlaybackPolicy.effectiveVolume(0.3), 0.0001f)
    }

    @Test
    fun effectiveVolume_defaultsTo04_andClampsNegative() {
        assertEquals(0.4f, SoundPlaybackPolicy.effectiveVolume(null), 0.0001f)
        assertEquals(0.4f, SoundPlaybackPolicy.effectiveVolume(Double.NaN), 0.0001f)
        assertEquals(0f, SoundPlaybackPolicy.effectiveVolume(-0.2), 0.0001f)
    }

    @Test
    fun duckedVolume_is30Percent() {
        assertEquals(0.12f, SoundPlaybackPolicy.duckedVolume(0.4f, ducking = true), 0.0001f)
        assertEquals(0.4f, SoundPlaybackPolicy.duckedVolume(0.4f, ducking = false), 0.0001f)
    }

    @Test
    fun isDuplicate_onlyForSameId() {
        assertTrue(SoundPlaybackPolicy.isDuplicate("a", "a"))
        assertFalse(SoundPlaybackPolicy.isDuplicate("a", "b"))
        assertFalse(SoundPlaybackPolicy.isDuplicate(null, "a"))
    }

    @Test
    fun onModeChange_stopsWhenOffOrSwitched() {
        assertEquals(ModeChangeAction.STOP, SoundPlaybackPolicy.onModeChange(SoundMode.AMBIENT, SoundMode.OFF))
        assertEquals(ModeChangeAction.STOP, SoundPlaybackPolicy.onModeChange(SoundMode.MUSIC, SoundMode.OFF))
        assertEquals(ModeChangeAction.STOP, SoundPlaybackPolicy.onModeChange(SoundMode.AMBIENT, SoundMode.MUSIC))
        assertEquals(ModeChangeAction.STOP, SoundPlaybackPolicy.onModeChange(SoundMode.MUSIC, SoundMode.AMBIENT))
    }

    @Test
    fun onModeChange_keepsOtherwise() {
        assertEquals(ModeChangeAction.KEEP, SoundPlaybackPolicy.onModeChange(SoundMode.OFF, SoundMode.AMBIENT))
        assertEquals(ModeChangeAction.KEEP, SoundPlaybackPolicy.onModeChange(SoundMode.AMBIENT, SoundMode.AMBIENT))
        assertEquals(ModeChangeAction.KEEP, SoundPlaybackPolicy.onModeChange(SoundMode.OFF, SoundMode.OFF))
    }
}
