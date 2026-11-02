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
 * 앱이 TalkBack의 말을 멈출 수는 없으므로, 씨나리오 TTS는 TalkBack이 말을 마칠 때까지 기다렸다가 말한다.
 * TalkBack 음성은 `USAGE_ASSISTANCE_ACCESSIBILITY` 용도로 재생되므로, 그 용도의 재생이 있는지로 판단한다.
 * 재생 상태(isActive)는 Android 11(API 30)부터 볼 수 있어서, 그보다 낮은 기기에서는 기다리지 않는다.
 */
class TalkBackSpeechMonitor(context: Context, handler: Handler) {

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
            update(audioManager.activePlaybackConfigurations)
        }
    }

    /**
     * TalkBack이 켜져 있고, 지금 말하는 중이거나 말을 멈춘 지 [quietMs]가 안 됐으면 true.
     * TalkBack은 문장 사이에 잠깐 멈추므로, 잠깐 조용한 것만으로는 끝났다고 보지 않는다.
     */
    fun isSpeaking(quietMs: Long): Boolean {
        if (accessibilityManager?.isTouchExplorationEnabled != true) return false
        if (active) return true
        return SystemClock.uptimeMillis() - lastActiveAt < quietMs
    }

    /**
     * TalkBack에 지금 하던 말을 멈추라고 요청한다(대기 중인 말도 함께 버려진다).
     * 화면을 만지거나 두 손가락으로 탭했을 때와 같은 효과다.
     */
    fun interrupt() {
        val manager = accessibilityManager ?: return
        if (!manager.isEnabled) return
        manager.interrupt()
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            audioManager?.unregisterAudioPlaybackCallback(callback)
        }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val nowActive = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY }
        if (active || nowActive) lastActiveAt = SystemClock.uptimeMillis()
        active = nowActive
    }
}
