package com.buzzerbeater.seenario.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager

/**
 * TalkBack 같은 화면 낭독기가 지금 말하고 있는지 알려 준다.
 *
 * 씨나리오 TTS는 TalkBack이 말하는 중이면 [interrupt]로 멈추게 한 뒤, 조용해지면 말한다.
 * TalkBack 음성은 `USAGE_ASSISTANCE_ACCESSIBILITY` 용도로 재생되므로, 그 용도의 재생이 있는지로 판단한다.
 * 재생 상태 콜백은 지금 재생 중인 플레이어만 넘겨 준다(Android 15에서 확인). 그보다 오래된 기기에서의 동작은
 * 확인하지 못해서, Android 11(API 30) 미만에서는 TalkBack을 기다리거나 멈추지 않고 바로 말한다.
 */
class TalkBackSpeechMonitor(
    context: Context,
    handler: Handler,
    /** TalkBack이 조용하다가 말하기 시작했을 때(메인 스레드). */
    private val onStartedSpeaking: () -> Unit,
    /** TalkBack이 말하다가 멈췄을 때(메인 스레드). 문장 사이 쉼에도 불릴 수 있다. */
    private val onStoppedSpeaking: () -> Unit = {},
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)

    private var active = false
    private var lastActiveAt = 0L

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            update(configs)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && audioManager != null) {
            audioManager.registerAudioPlaybackCallback(callback, handler)
            update(audioManager.activePlaybackConfigurations, notify = false)
        }
    }

    /**
     * TalkBack이 켜져 있고, 지금 말하는 중이거나 말을 멈춘 지 [quietMs]가 안 됐으면 true.
     * TalkBack은 문장 사이에 잠깐 멈추므로, 잠깐 조용한 것만으로는 끝났다고 보지 않는다.
     */
    fun isSpeaking(quietMs: Long): Boolean {
        if (!isEnabled) return false
        if (active) return true
        return SystemClock.uptimeMillis() - lastActiveAt < quietMs
    }

    /**
     * TalkBack에 지금 하던 말을 멈추라고 요청한다(대기 중인 말도 함께 버려진다).
     * 화면을 만지거나 두 손가락으로 탭했을 때와 같은 효과다.
     */
    fun interrupt() {
        if (!isEnabled) return
        accessibilityManager?.interrupt()
    }

    /** TalkBack(터치 탐색)이 켜져 있는지. */
    val isEnabled: Boolean
        get() = accessibilityManager?.isTouchExplorationEnabled == true

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            audioManager?.unregisterAudioPlaybackCallback(callback)
        }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>, notify: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val nowActive = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY }
        if (active || nowActive) lastActiveAt = SystemClock.uptimeMillis()
        val started = nowActive && !active
        val stopped = !nowActive && active
        active = nowActive
        if (notify && started && isEnabled) onStartedSpeaking()
        if (notify && stopped) onStoppedSpeaking()
    }
}
