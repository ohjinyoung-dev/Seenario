package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode

/** 소리 재생 규칙(안드로이드에 의존하지 않아 단위 테스트할 수 있다). */
object SoundPlaybackPolicy {

    /** action에 volume이 없을 때 쓰는 볼륨. */
    const val DEFAULT_VOLUME = 0.4f

    /** 효과음 볼륨 상한. 주변 소리를 가리지 않도록 이보다 크게 틀지 않는다. */
    const val MAX_VOLUME = 0.5f

    /** TTS나 TalkBack이 말하는 동안 원래 볼륨의 이만큼으로 낮춘다. */
    const val DUCK_RATIO = 0.3f

    /** action의 volume을 실제 재생 볼륨(0 ~ [MAX_VOLUME])으로 바꾼다. 없거나 숫자가 아니면 [DEFAULT_VOLUME]. */
    fun effectiveVolume(volume: Double?): Float {
        if (volume == null || volume.isNaN()) return DEFAULT_VOLUME
        return volume.toFloat().coerceIn(0f, MAX_VOLUME)
    }

    /** 낮춤 여부에 따른 볼륨. */
    fun duckedVolume(volume: Float, ducking: Boolean): Float = if (ducking) volume * DUCK_RATIO else volume

    /** 이미 재생 중(또는 받는 중)인 음원 [activeId]와 같은 음원이면 다시 시작하지 않는다. */
    fun isDuplicate(activeId: String?, newId: String): Boolean = activeId != null && activeId == newId

    /** 소리 모드가 바뀔 때 지금 소리를 어떻게 할지. */
    fun onModeChange(old: SoundMode, new: SoundMode): ModeChangeAction = when {
        old == new -> ModeChangeAction.KEEP
        // 기본 모드로 바뀌면 멈춘다.
        new == SoundMode.OFF -> ModeChangeAction.STOP
        // 환경음 ↔ 배경음: 멈추고 다음 응답의 play_sound를 기다린다.
        old != SoundMode.OFF -> ModeChangeAction.STOP
        // 기본 모드 → 환경음/배경음: 재생 중인 소리가 없다.
        else -> ModeChangeAction.KEEP
    }

    enum class ModeChangeAction { KEEP, STOP }
}
