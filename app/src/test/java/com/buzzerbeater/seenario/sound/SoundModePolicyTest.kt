package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundModePolicyTest {

    private val greeting = "씨나리오가 준비됐어요."

    @Test
    fun modeForSoundOn() {
        assertEquals(SoundMode.AMBIENT, SoundModePolicy.modeForSoundOn(SoundMode.AMBIENT))
        assertEquals(SoundMode.MUSIC, SoundModePolicy.modeForSoundOn(SoundMode.MUSIC))
        assertEquals(SoundMode.AMBIENT, SoundModePolicy.modeForSoundOn(SoundMode.OFF))
        assertEquals(SoundMode.AMBIENT, SoundModePolicy.modeForSoundOn(null))
    }

    @Test
    fun startupGreeting_lastAmbient_appendsAmbientLine() {
        assertEquals(
            "$greeting 지난번에는 환경음 모드를 쓰셨어요. '환경음 켜'라고 말하면 다시 켜져요.",
            SoundModePolicy.startupGreeting(greeting, SoundMode.AMBIENT),
        )
    }

    @Test
    fun startupGreeting_lastMusic_appendsMusicLine() {
        assertEquals(
            "$greeting 지난번에는 배경음 모드를 쓰셨어요. '배경음 켜'라고 말하면 다시 켜져요.",
            SoundModePolicy.startupGreeting(greeting, SoundMode.MUSIC),
        )
    }

    @Test
    fun startupGreeting_lastOffOrNothing_unchanged() {
        assertEquals(greeting, SoundModePolicy.startupGreeting(greeting, SoundMode.OFF))
        assertEquals(greeting, SoundModePolicy.startupGreeting(greeting, null))
        assertNull(SoundModePolicy.lastModeSuggestion(SoundMode.OFF))
        assertNull(SoundModePolicy.lastModeSuggestion(null))
    }
}
