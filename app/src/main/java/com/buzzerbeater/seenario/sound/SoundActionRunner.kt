package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.SoundMode

/**
 * 에이전트 응답(대화 응답, 조용한 소리 요청 응답)의 소리 지시를 [SoundRequestOrder] 규칙에 따라 [player]로 실행한다.
 * 안드로이드에 의존하지 않아 단위 테스트할 수 있다.
 */
class SoundActionRunner(
    private val player: SoundPlayer,
    private val order: SoundRequestOrder = SoundRequestOrder(),
    private val log: (String) -> Unit = SoundLog::d,
) {
    /** 소리를 바꿀 수 있는 요청을 보낼 때 받는 번호. */
    fun nextRequest(): Int = order.next()

    /**
     * [seq] 요청의 응답 [actions]를 실행한다. 새 소리를 틀기 시작하면 true.
     * [blockReason]은 부르는 쪽이 판단한 play_sound 무시 이유(모드가 바뀜, 앱이 화면에 없음 등). 없으면 null.
     *
     * - play_sound: 막는 이유가 없고 더 최근 요청의 소리 지시가 실행되지 않았으면 재생한다.
     * - stop_sound: 더 최근 요청의 소리 지시가 실행되지 않았으면 멈춘다.
     * 실행한 요청은 "정해진 번호"가 되어, 그보다 먼저 보낸 요청의 늦은 소리 지시는 버린다.
     */
    fun run(actions: List<AgentAction>, seq: Int, blockReason: () -> String? = { null }): Boolean {
        var willPlay = false
        actions.forEach { action ->
            when (action.type) {
                AgentAction.PLAY_SOUND -> {
                    val reason = blockReason() ?: staleReason(seq)
                    if (reason != null) {
                        log("play_sound 무시: $reason, sound_id=${action.soundId}")
                        return@forEach
                    }
                    order.markDecided(seq)
                    if (player.play(action)) willPlay = true
                }
                AgentAction.STOP_SOUND -> {
                    val reason = staleReason(seq)
                    if (reason != null) {
                        log("stop_sound 무시: $reason")
                        return@forEach
                    }
                    order.markDecided(seq)
                    player.handle(action)
                }
                else -> player.handle(action)
            }
        }
        return willPlay
    }

    /**
     * 사용자가 직접 소리 모드를 바꿨을 때("소리 꺼", "조용히 해줘", "기본 모드로", 환경음 ↔ 배경음).
     * 요청 순서 규칙과 상관없이 바로 적용한다(기본 모드나 다른 모드로 바뀌면 페이드로 멈춤).
     */
    fun onUserSoundModeChanged(old: SoundMode, new: SoundMode) = player.onSoundModeChanged(old, new)

    private fun staleReason(seq: Int): String? =
        if (order.canApply(seq)) null else "더 최근 요청(#${order.decided})의 소리 지시가 이미 실행됨(이 요청 #$seq)"
}
