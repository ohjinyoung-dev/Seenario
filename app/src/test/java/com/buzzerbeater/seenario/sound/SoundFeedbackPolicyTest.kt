package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.sound.SoundFeedbackPolicy.Outcome
import com.buzzerbeater.seenario.sound.SoundFeedbackPolicy.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundFeedbackPolicyTest {

    /** 소리 요청을 보낸 뒤 아무 일도 없었던 상황. 테스트마다 한 가지씩 바꾼다. */
    private fun quiet(mode: SoundMode = SoundMode.AMBIENT) = Situation(
        requestMode = mode,
        currentMode = mode,
        conversationNow = false,
        conversationSinceRequest = false,
        soundActive = false,
    )

    private val ambientNoSound =
        "지금 장면에 어울리는 환경음을 찾지 못했어요. 다른 곳으로 옮기거나 다른 풍경을 비춘 뒤 '환경음 켜'라고 다시 말해 주세요."
    private val musicNoSound =
        "지금 장면에 어울리는 배경음을 찾지 못했어요. 다른 곳으로 옮기거나 다른 풍경을 비춘 뒤 '배경음 켜'라고 다시 말해 주세요."

    // --- 응답 결과 구분 ---

    @Test
    fun outcomeOf_playSound_noPlaySound_failed() {
        assertEquals(Outcome.SOUND, SoundFeedbackPolicy.outcomeOf(listOf(AgentAction(type = AgentAction.PLAY_SOUND))))
        assertEquals(Outcome.NO_SOUND, SoundFeedbackPolicy.outcomeOf(emptyList()))
        // stop_sound만 있어도 새로 틀 소리는 없다.
        assertEquals(Outcome.NO_SOUND, SoundFeedbackPolicy.outcomeOf(listOf(AgentAction(type = AgentAction.STOP_SOUND))))
        assertEquals(Outcome.FAILED, SoundFeedbackPolicy.outcomeOf(null))
    }

    // --- 소리 없음 → 안내 ---

    @Test
    fun noSound_announcesForEachMode() {
        assertEquals(ambientNoSound, SoundFeedbackPolicy.decide(Outcome.NO_SOUND, quiet(SoundMode.AMBIENT)).message)
        assertEquals(musicNoSound, SoundFeedbackPolicy.decide(Outcome.NO_SOUND, quiet(SoundMode.MUSIC)).message)
    }

    @Test
    fun soundFound_noAnnouncement() {
        assertNull(SoundFeedbackPolicy.decide(Outcome.SOUND, quiet()).message)
    }

    @Test
    fun failed_announcesFailureForEachMode() {
        assertEquals(
            "소리를 불러오지 못했어요. 잠시 후 '환경음 켜'라고 다시 말해 주세요.",
            SoundFeedbackPolicy.decide(Outcome.FAILED, quiet(SoundMode.AMBIENT)).message,
        )
        assertEquals(
            "소리를 불러오지 못했어요. 잠시 후 '배경음 켜'라고 다시 말해 주세요.",
            SoundFeedbackPolicy.decide(Outcome.FAILED, quiet(SoundMode.MUSIC)).message,
        )
    }

    // --- 대화 중이면 안내 안 함 ---

    @Test
    fun conversationNow_noAnnouncement() {
        val talking = quiet().copy(conversationNow = true)
        assertNull(SoundFeedbackPolicy.decide(Outcome.NO_SOUND, talking).message)
        assertNull(SoundFeedbackPolicy.decide(Outcome.FAILED, talking).message)
    }

    @Test
    fun conversationSinceRequest_noAnnouncement() {
        val talked = quiet().copy(conversationSinceRequest = true)
        assertNull(SoundFeedbackPolicy.decide(Outcome.NO_SOUND, talked).message)
        assertNull(SoundFeedbackPolicy.decide(Outcome.FAILED, talked).message)
    }

    // --- 그 사이 모드가 바뀌면 안내 안 함 ---

    @Test
    fun modeChangedToOff_noAnnouncement() {
        val off = quiet(SoundMode.AMBIENT).copy(currentMode = SoundMode.OFF)
        assertNull(SoundFeedbackPolicy.decide(Outcome.NO_SOUND, off).message)
        assertNull(SoundFeedbackPolicy.decide(Outcome.FAILED, off).message)
    }

    @Test
    fun modeChangedToOtherMode_noAnnouncement() {
        val switched = quiet(SoundMode.AMBIENT).copy(currentMode = SoundMode.MUSIC)
        assertNull(SoundFeedbackPolicy.decide(Outcome.NO_SOUND, switched).message)
        assertNull(SoundFeedbackPolicy.decide(Outcome.FAILED, switched).message)
    }

    // --- 그 사이 소리가 켜졌으면 안내 안 함 ---

    @Test
    fun soundAlreadyPlaying_noAnnouncement() {
        val playing = quiet().copy(soundActive = true)
        assertNull(SoundFeedbackPolicy.decide(Outcome.NO_SOUND, playing).message)
        assertNull(SoundFeedbackPolicy.decide(Outcome.FAILED, playing).message)
    }

    // --- 같은 모드를 다시 켜면 소리 요청을 다시 보냄 ---

    @Test
    fun sameModeAgain_requestsSoundWithoutModeNotice() {
        for (mode in listOf(SoundMode.AMBIENT, SoundMode.MUSIC)) {
            val plan = SoundFeedbackPolicy.planModeCommand(old = mode, new = mode)
            assertTrue(plan.requestSound)
            assertTrue(plan.sameModeAgain)
            assertFalse(plan.announceMode)
        }
    }

    @Test
    fun turningOnFromOtherMode_announcesAndRequests() {
        val plan = SoundFeedbackPolicy.planModeCommand(old = SoundMode.OFF, new = SoundMode.AMBIENT)
        assertTrue(plan.announceMode)
        assertTrue(plan.requestSound)
        assertFalse(plan.sameModeAgain)
        val switch = SoundFeedbackPolicy.planModeCommand(old = SoundMode.AMBIENT, new = SoundMode.MUSIC)
        assertTrue(switch.announceMode)
        assertTrue(switch.requestSound)
        assertFalse(switch.sameModeAgain)
    }

    @Test
    fun turningOff_announcesWithoutRequest_evenIfAlreadyOff() {
        for (old in SoundMode.entries) {
            val plan = SoundFeedbackPolicy.planModeCommand(old = old, new = SoundMode.OFF)
            assertTrue(plan.announceMode)
            assertFalse(plan.requestSound)
            assertFalse(plan.sameModeAgain)
        }
    }
}
