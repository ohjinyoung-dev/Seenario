package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.sound.SoundModeSwipe.Mode
import com.buzzerbeater.seenario.sound.SoundModeSwipe.Touch
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundModeSwipeTest {

    private val width = 1080f
    private val tolerance = 84f

    private fun classify(dx: Float, dy: Float) = SoundModeSwipe.classify(dx, dy, width, tolerance)

    @Test
    fun `다음 모드는 기본 감성 환경음 배경음 순서로 돈다`() {
        val basic = Mode(SoundMode.OFF)
        val emotional = Mode(SoundMode.OFF, emotional = true)
        assertEquals(emotional, SoundModeSwipe.next(basic))
        assertEquals(Mode(SoundMode.AMBIENT), SoundModeSwipe.next(emotional))
        assertEquals(Mode(SoundMode.MUSIC), SoundModeSwipe.next(Mode(SoundMode.AMBIENT)))
        assertEquals(basic, SoundModeSwipe.next(Mode(SoundMode.MUSIC)))
    }

    @Test
    fun `허용 범위 안의 작은 움직임은 탭으로 본다`() {
        assertEquals(Touch.STILL, classify(0f, 0f))
        assertEquals(Touch.STILL, classify(30f, -20f))
        assertEquals(Touch.STILL, classify(84f, 0f))
    }

    @Test
    fun `가로로 화면 4분의 1 이상 밀면 방향과 상관없이 스와이프다`() {
        assertEquals(Touch.SWIPE, classify(-270f, 0f))
        assertEquals(Touch.SWIPE, classify(270f, 0f))
        assertEquals(Touch.SWIPE, classify(-500f, 100f))
    }

    @Test
    fun `허용 범위는 넘었지만 가로로 충분히 밀지 않으면 애매한 움직임이다`() {
        assertEquals(Touch.AMBIGUOUS, classify(200f, 0f))
        assertEquals(Touch.AMBIGUOUS, classify(-269f, 10f))
    }

    @Test
    fun `세로 움직임이 크면 스와이프가 아니다`() {
        assertEquals(Touch.AMBIGUOUS, classify(0f, 600f))
        assertEquals(Touch.AMBIGUOUS, classify(400f, 201f))
        assertEquals(Touch.SWIPE, classify(400f, 200f))
    }
}
