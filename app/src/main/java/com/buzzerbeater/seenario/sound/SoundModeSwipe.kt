package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 화면을 가로로 밀어 소리 모드를 바꾸는 규칙. 안드로이드에 의존하지 않아 단위 테스트할 수 있다.
 *
 * - 방향과 상관없이 가로로 밀면 항상 다음 모드: 기본 → 감성 → 환경음 → 배경음 → 기본.
 *   기본 모드(심플)와 감성 모드는 둘 다 소리 모드 off다. 기본은 1~2문장, 감성은 3~4문장으로 묘사한다.
 * - 손가락이 탭 허용 범위 안에서만 움직였으면 탭/길게 누르기, 가로로 충분히 밀었으면 스와이프,
 *   그 사이(애매한 움직임)는 아무것도 하지 않는다.
 */
object SoundModeSwipe {

    /** 마지막 스와이프 뒤 이만큼 더 밀지 않으면 그때 소리 요청을 한 번 보낸다(빠르게 넘길 때 요청이 쌓이지 않게). */
    const val REQUEST_DELAY_MS = 1_000L

    /** 스와이프로 보려면 가로로 화면 너비의 이 비율 이상 움직여야 한다. */
    const val MIN_WIDTH_RATIO = 0.25f

    /** 스와이프로 보려면 가로 이동이 세로 이동의 이 배수 이상이어야 한다. */
    const val MIN_HORIZONTAL_RATIO = 2f

    /** 손을 뗐을 때 터치의 종류. */
    enum class Touch {
        /** 탭 허용 범위 안에서만 움직임(탭이나 길게 누르기). */
        STILL,

        /** 가로 스와이프(다음 모드). */
        SWIPE,

        /** 탭도 스와이프도 아닌 움직임. 무시한다. */
        AMBIGUOUS,
    }

    /** 스와이프로 도는 모드 하나. [emotional](감성 모드)은 [soundMode]가 OFF일 때만 true다. */
    data class Mode(val soundMode: SoundMode, val emotional: Boolean = false)

    fun next(mode: Mode): Mode = when (mode.soundMode) {
        SoundMode.OFF -> if (mode.emotional) Mode(SoundMode.AMBIENT) else Mode(SoundMode.OFF, emotional = true)
        SoundMode.AMBIENT -> Mode(SoundMode.MUSIC)
        SoundMode.MUSIC -> Mode(SoundMode.OFF)
    }

    /** 누른 곳에서 뗀 곳까지 [dx], [dy]만큼 움직였을 때의 터치 종류. */
    fun classify(dx: Float, dy: Float, screenWidth: Float, tapTolerance: Float): Touch = when {
        hypot(dx, dy) <= tapTolerance -> Touch.STILL
        abs(dx) >= screenWidth * MIN_WIDTH_RATIO && abs(dx) >= abs(dy) * MIN_HORIZONTAL_RATIO -> Touch.SWIPE
        else -> Touch.AMBIGUOUS
    }
}
