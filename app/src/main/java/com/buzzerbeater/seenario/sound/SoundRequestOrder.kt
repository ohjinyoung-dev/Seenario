package com.buzzerbeater.seenario.sound

/**
 * 소리를 바꿀 수 있는 요청(대화 요청, 조용한 소리 요청)의 순서 규칙: 최신 요청 우선.
 * 안드로이드에 의존하지 않아 단위 테스트할 수 있다.
 *
 * - 요청을 보낼 때 [next]로 번호를 받는다(보낸 순서대로 커진다).
 * - 어떤 요청의 소리 지시(play_sound 또는 stop_sound)가 실행되면 그 번호를 "정해진 번호"로 기록한다.
 * - 정해진 번호보다 먼저 보낸 요청의 소리 지시(play_sound, stop_sound)는 늦게 도착해도 버린다.
 *   예: 배경음 켜(#1) → 질문(#2) 답이 먼저 와서 다른 곡을 틀거나 소리를 끄면, 늦게 온 #1의 곡은 틀지 않는다.
 *   예: 질문(#1) → 배경음 켜(#2)로 곡이 나오는 중에 #1의 stop_sound가 늦게 와도 곡을 멈추지 않는다.
 * - 사용자가 직접 말한 명령("소리 꺼", "조용히 해줘", 모드 전환)은 이 규칙과 상관없다([SoundActionRunner.onUserSoundModeChanged]).
 */
class SoundRequestOrder {
    private var lastIssued = 0

    /** 소리 지시가 실행된 가장 최근 요청 번호. 아직 없으면 0. */
    var decided = 0
        private set

    /** 새 요청 번호. */
    fun next(): Int = ++lastIssued

    /** [seq] 요청의 소리 지시(play_sound, stop_sound)를 실행해도 되는지(더 최근 요청의 소리 지시가 이미 실행됐으면 false). */
    fun canApply(seq: Int): Boolean = seq >= decided

    /** [seq] 요청의 소리 지시(play_sound, stop_sound)를 실행했다. 정해진 번호는 줄어들지 않는다. */
    fun markDecided(seq: Int) {
        if (seq > decided) decided = seq
    }
}
