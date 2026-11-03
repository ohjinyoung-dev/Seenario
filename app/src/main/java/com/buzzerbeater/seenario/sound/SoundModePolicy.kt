package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode

/** 소리 모드 규칙(안드로이드에 의존하지 않아 단위 테스트할 수 있다). */
object SoundModePolicy {

    /** "소리 켜": 지난 모드가 환경음/배경음이면 그 모드로, 기본 모드(off)이거나 없으면 환경음으로. */
    fun modeForSoundOn(last: SoundMode?): SoundMode = when (last) {
        SoundMode.AMBIENT, SoundMode.MUSIC -> last
        SoundMode.OFF, null -> SoundMode.AMBIENT
    }

    /** 시작 안내 끝에 붙일 지난 모드 제안. 지난 모드가 기본 모드(off)이거나 없으면 null. */
    fun lastModeSuggestion(last: SoundMode?): String? = when (last) {
        SoundMode.AMBIENT -> "지난번에는 환경음 모드를 쓰셨어요. '환경음 켜'라고 말하면 다시 켜져요."
        SoundMode.MUSIC -> "지난번에는 배경음 모드를 쓰셨어요. '배경음 켜'라고 말하면 다시 켜져요."
        SoundMode.OFF, null -> null
    }

    /** 시작 안내 [greeting] 끝에 지난 모드 제안을 한 줄 붙인다. 붙일 게 없으면 그대로. */
    fun startupGreeting(greeting: String, last: SoundMode?): String =
        lastModeSuggestion(last)?.let { "$greeting $it" } ?: greeting
}
