package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.SoundMode

/**
 * 소리 모드를 켤 때(다시 켤 때 포함) 보낸 소리 요청의 결과를 사용자에게 알릴지 정하는 규칙.
 * 안드로이드에 의존하지 않아 단위 테스트할 수 있다.
 *
 * - 응답에 play_sound가 없으면 "어울리는 소리를 찾지 못했어요" 안내, 요청이 실패하면 "불러오지 못했어요" 안내.
 * - 묘사·대화 응답에는 쓰지 않는다(그 응답에 play_sound가 없는 건 지금처럼 조용히 넘어간다).
 * - 대화 중이거나 그 사이 대화가 있었으면, 모드가 바뀌었으면, 이미 소리가 나오고 있으면 안내하지 않는다.
 */
object SoundFeedbackPolicy {

    /** 소리 요청의 결과. */
    enum class Outcome {
        /** 응답에 play_sound가 있음. */
        SOUND,

        /** 응답은 왔지만 play_sound가 없음(어울리는 소리를 못 고름). */
        NO_SOUND,

        /** 요청 자체가 실패함(네트워크, 서버 에러, 시간 초과). */
        FAILED,
    }

    /** 결과를 알릴지 판단할 때의 상황. */
    data class Situation(
        /** 소리 요청을 보낼 때의 모드. */
        val requestMode: SoundMode,
        /** 지금 모드. */
        val currentMode: SoundMode,
        /** 지금 듣는 중, 생각하는 중, 말하는 중인지. */
        val conversationNow: Boolean,
        /** 소리 요청을 보낸 뒤로 듣기나 에이전트 요청(대화·묘사)이 있었는지. */
        val conversationSinceRequest: Boolean,
        /** 이미 소리가 나오고 있는지(그 사이 다른 요청으로 켜졌거나, 다시 요청하기 전부터 나오던 소리). */
        val soundActive: Boolean,
    )

    /** 알릴 말([message], 없으면 null)과 로그에 남길 이유. */
    data class Decision(val message: String?, val reason: String)

    /** 모드 명령("환경음 켜" 등)을 받았을 때 할 일. */
    data class ModeCommandPlan(
        /** "환경음 모드로 바꿨어요" 같은 모드 안내를 할지. */
        val announceMode: Boolean,
        /** 소리 요청을 보낼지. */
        val requestSound: Boolean,
        /** 같은 모드를 다시 켜서 소리 요청을 다시 보내는 경우인지(로그용). */
        val sameModeAgain: Boolean,
    )

    fun outcomeOf(actions: List<AgentAction>?): Outcome = when {
        actions == null -> Outcome.FAILED
        actions.any { it.type == AgentAction.PLAY_SOUND } -> Outcome.SOUND
        else -> Outcome.NO_SOUND
    }

    /**
     * [old]에서 [new]로 바꾸라는 명령. 소리 모드를 켜면 항상 지금 장면으로 소리 요청을 보낸다.
     * 이미 같은 환경음/배경음 모드면 모드 안내 없이 소리 요청만 다시 보낸다.
     * 기본 모드는 지금처럼 같은 모드여도 "기본 모드로 바꿨어요"라고 알린다.
     */
    fun planModeCommand(old: SoundMode, new: SoundMode): ModeCommandPlan {
        val sameModeAgain = old == new && new != SoundMode.OFF
        return ModeCommandPlan(
            announceMode = !sameModeAgain,
            requestSound = new != SoundMode.OFF,
            sameModeAgain = sameModeAgain,
        )
    }

    fun decide(outcome: Outcome, situation: Situation): Decision {
        if (outcome == Outcome.SOUND) return Decision(null, "play_sound 있음")
        val mode = situation.requestMode
        return when {
            situation.currentMode != mode -> Decision(null, "그 사이 모드가 바뀜(${mode.wire} → ${situation.currentMode.wire})")
            situation.conversationNow -> Decision(null, "대화 중")
            situation.conversationSinceRequest -> Decision(null, "그 사이 대화가 있었음")
            situation.soundActive -> Decision(null, "이미 소리가 나오고 있음")
            outcome == Outcome.NO_SOUND -> Decision(noSoundMessage(mode), "play_sound 없음")
            else -> Decision(failedMessage(mode), "소리 요청 실패")
        }
    }

    fun noSoundMessage(mode: SoundMode): String? = modeName(mode)?.let {
        "지금 장면에 어울리는 ${it}을 찾지 못했어요. 다른 곳으로 옮기거나 다른 풍경을 비춘 뒤 '$it 켜'라고 다시 말해 주세요."
    }

    fun failedMessage(mode: SoundMode): String? = modeName(mode)?.let {
        "소리를 불러오지 못했어요. 잠시 후 '$it 켜'라고 다시 말해 주세요."
    }

    private fun modeName(mode: SoundMode): String? = when (mode) {
        SoundMode.AMBIENT -> "환경음"
        SoundMode.MUSIC -> "배경음"
        SoundMode.OFF -> null
    }
}
