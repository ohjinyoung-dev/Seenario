package com.buzzerbeater.seenario.agent

import kotlinx.coroutines.delay

/**
 * 서버(`seenario-agent`)가 생기기 전까지 쓰는 가짜 에이전트. [delayMs] 뒤에 예시 응답을 돌려준다.
 * 소리 모드가 켜져 있으면 play_sound action 예시도 넣는다(url은 없음).
 */
class FakeAgentClient(private val delayMs: Long = 2_000L) : AgentClient {

    override suspend fun send(request: AgentRequest): AgentResponse {
        delay(delayMs)
        val text = when (request.inputType) {
            InputType.DESCRIBE -> DESCRIBE_SAMPLE
            InputType.SPEECH -> "'${request.input}'라고 물어보셨네요. " + SPEECH_SAMPLE
        }
        return AgentResponse(
            speech = text,
            display = text,
            actions = soundAction(request.soundMode)?.let(::listOf).orEmpty(),
        )
    }

    private fun soundAction(mode: SoundMode): AgentAction? = when (mode) {
        SoundMode.OFF -> null
        SoundMode.AMBIENT -> AgentAction(
            type = AgentAction.PLAY_SOUND,
            soundId = "fake-ambient-forest",
            title = "숲속 바람 소리",
            volume = 0.4,
            loopable = true,
        )
        SoundMode.MUSIC -> AgentAction(
            type = AgentAction.PLAY_SOUND,
            soundId = "fake-music-calm-piano",
            title = "잔잔한 피아노",
            volume = 0.3,
            loopable = true,
        )
    }

    private companion object {
        const val DESCRIBE_SAMPLE =
            "창가로 오후 햇살이 비스듬히 들어와 나무 책상 위에 따뜻한 빛을 펼쳐 놓았어요. " +
                "조용한 방 안에 바깥의 부드러운 바람이 살짝 스며드는 듯한, 느긋한 오후예요."
        const val SPEECH_SAMPLE =
            "지금은 연습용 답변이에요. 에이전트가 완성되면 주변 모습을 보고 대답해 드릴게요."
    }
}
