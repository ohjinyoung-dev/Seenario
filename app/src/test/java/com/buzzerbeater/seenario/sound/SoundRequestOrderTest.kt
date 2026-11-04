package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.SoundMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 최신 요청 우선 규칙. "배경음 켜"(조용한 소리 요청)와 그 뒤에 한 질문(대화 요청)의 응답이 어떤 순서로 와도
 * 더 나중에 보낸 요청의 소리 지시가 이긴다.
 */
class SoundRequestOrderTest {

    private val engines = mutableListOf<FakeEngine>()
    private val logs = mutableListOf<String>()

    private fun runner(scope: CoroutineScope): Pair<SoundActionRunner, SoundPlayer> {
        val player = SoundPlayer(
            scope = scope,
            source = FakeSource(),
            engineFactory = { FakeEngine().also { engines += it } },
            fadeMs = 0,
            duckMs = 0,
            log = { logs += it },
        )
        return SoundActionRunner(player, log = { logs += it }) to player
    }

    private fun play(id: String) =
        AgentAction(type = AgentAction.PLAY_SOUND, soundId = id, title = id, url = "https://example.invalid/$id", loopable = true)

    private val stop = AgentAction(type = AgentAction.STOP_SOUND)

    // --- SoundRequestOrder 자체 ---

    @Test
    fun order_numbersIncrease() {
        val order = SoundRequestOrder()
        assertEquals(1, order.next())
        assertEquals(2, order.next())
    }

    @Test
    fun order_olderPlayRejectedAfterNewerDecided() {
        val order = SoundRequestOrder()
        val sound = order.next()
        val question = order.next()
        order.markDecided(question)
        assertFalse(order.canApply(sound))
        assertTrue(order.canApply(question))
    }

    @Test
    fun order_decidedNeverGoesBack() {
        val order = SoundRequestOrder()
        order.markDecided(3)
        order.markDecided(1)
        assertEquals(3, order.decided)
    }

    // --- SoundActionRunner + SoundPlayer ---

    @Test
    fun questionAnswerFirstWithOtherTrack_lateSoundResponseIsDropped() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest() // 배경음 켜
        val question = runner.nextRequest() // 고르는 중에 한 질문

        // 질문 답이 먼저 와서 다른 곡을 튼다.
        assertTrue(runner.run(listOf(play("piano")), question))
        // 늦게 온 sound 응답의 곡은 버린다.
        assertFalse(runner.run(listOf(play("debussy")), sound))

        assertEquals("piano", player.activeSoundId)
        assertEquals(1, engines.size)
        assertFalse(engines[0].stopped)
        assertTrue(logs.any { it.startsWith("play_sound 무시: 더 최근 요청(#2)") })
        player.release()
    }

    @Test
    fun soundResponseFirst_thenNewerQuestionTrackReplacesIt() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest()
        val question = runner.nextRequest()

        assertTrue(runner.run(listOf(play("debussy")), sound))
        assertTrue(runner.run(listOf(play("piano")), question))

        assertEquals("piano", player.activeSoundId)
        assertTrue(engines[0].stopped)
        player.release()
    }

    @Test
    fun questionAnswerWithoutSound_lateSoundResponseStillPlays() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest()
        val question = runner.nextRequest()

        // 질문 답에 소리 지시가 없으면 순서에 영향을 주지 않는다.
        assertFalse(runner.run(emptyList(), question))
        assertTrue(runner.run(listOf(play("debussy")), sound))

        assertEquals("debussy", player.activeSoundId)
        player.release()
    }

    @Test
    fun newerStopSound_lateSoundResponseIsDropped() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest() // 배경음 켜
        val question = runner.nextRequest() // 예: "음악 좀 꺼 줘"가 에이전트로 감

        runner.run(listOf(stop), question)
        assertFalse(runner.run(listOf(play("debussy")), sound))

        assertNull(player.activeSoundId)
        assertTrue(engines.isEmpty())
        player.release()
    }

    @Test
    fun newerStopSound_stopsWhatIsPlaying() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest()
        val question = runner.nextRequest()

        runner.run(listOf(play("debussy")), sound)
        runner.run(listOf(stop), question)

        assertNull(player.activeSoundId)
        assertTrue(engines[0].stopped)
        player.release()
    }

    @Test
    fun laterRequestAfterStop_canPlayAgain() = runBlocking {
        val (runner, player) = runner(this)
        val question = runner.nextRequest()
        runner.run(listOf(stop), question)
        val next = runner.nextRequest() // 다시 "배경음 켜"
        assertTrue(runner.run(listOf(play("debussy")), next))
        assertEquals("debussy", player.activeSoundId)
        player.release()
    }

    @Test
    fun blockReason_dropsPlay_andDoesNotDecide() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest()
        val question = runner.nextRequest()

        // 모드가 바뀌어 질문 답의 곡은 버려진다 → 정해진 번호로 기록되지 않는다.
        assertFalse(runner.run(listOf(play("piano")), question) { "모드 바뀜" })
        assertTrue(runner.run(listOf(play("debussy")), sound))
        assertEquals("debussy", player.activeSoundId)
        player.release()
    }

    @Test
    fun sameTrackFromNewerRequest_doesNotRestart_andLogs() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest()
        val question = runner.nextRequest()

        runner.run(listOf(play("debussy")), sound)
        assertFalse(runner.run(listOf(play("debussy")), question))

        assertEquals(1, engines.size)
        assertTrue(logs.any { it.startsWith("같은 음원이 이미 재생 중") })
        player.release()
    }

    @Test
    fun startWhileDucking_logsDucking() = runBlocking {
        val (runner, player) = runner(this)
        player.setDucking(true)
        runner.run(listOf(play("debussy")), runner.nextRequest())
        assertTrue(logs.any { it.startsWith("볼륨 낮춤(말하는 중에 재생 시작)") })
        player.release()
    }
    // --- 늦게 온 오래된 stop_sound ---

    @Test
    fun olderStopAfterNewerPlay_isIgnored() = runBlocking {
        val (runner, player) = runner(this)
        val question = runner.nextRequest() // 질문(에이전트가 나중에 stop_sound를 보냄)
        val sound = runner.nextRequest() // 그 뒤 "배경음 켜"

        assertTrue(runner.run(listOf(play("debussy")), sound))
        // 먼저 보낸 질문의 stop_sound가 늦게 와도 더 최근 요청이 튼 곡을 멈추지 않는다.
        runner.run(listOf(stop), question)

        assertEquals("debussy", player.activeSoundId)
        assertFalse(engines[0].stopped)
        assertTrue(logs.any { it.startsWith("stop_sound 무시: 더 최근 요청(#2)") })
        player.release()
    }

    @Test
    fun olderStopAfterNewerStop_isIgnoredToo() = runBlocking {
        val (runner, player) = runner(this)
        val first = runner.nextRequest()
        val second = runner.nextRequest()
        runner.run(listOf(stop), second)
        runner.run(listOf(stop), first)
        assertTrue(logs.any { it.startsWith("stop_sound 무시") })
        player.release()
    }

    // --- 사용자가 직접 말한 명령은 순서 규칙과 상관없이 바로 멈춤 ---

    @Test
    fun userDefaultMode_alwaysStops_evenWhenNewestRequestPlayed() = runBlocking {
        val (runner, player) = runner(this)
        val sound = runner.nextRequest()
        runner.run(listOf(play("debussy")), sound)

        // "소리 꺼" / "조용히 해줘" / "기본 모드로 가줘"는 모두 기본 모드(off) 전환이다.
        runner.onUserSoundModeChanged(SoundMode.MUSIC, SoundMode.OFF)

        assertNull(player.activeSoundId)
        assertTrue(engines[0].stopped)
        player.release()
    }

    @Test
    fun userDefaultMode_stopsEvenAfterOlderStopWasIgnored() = runBlocking {
        val (runner, player) = runner(this)
        val question = runner.nextRequest()
        val sound = runner.nextRequest()
        runner.run(listOf(play("debussy")), sound)
        runner.run(listOf(stop), question) // 무시됨
        assertFalse(engines[0].stopped)

        runner.onUserSoundModeChanged(SoundMode.MUSIC, SoundMode.OFF)

        assertTrue(engines[0].stopped)
        player.release()
    }

    @Test
    fun userModeSwitch_ambientToMusic_alwaysStops() = runBlocking {
        val (runner, player) = runner(this)
        runner.run(listOf(play("waves")), runner.nextRequest())
        runner.onUserSoundModeChanged(SoundMode.AMBIENT, SoundMode.MUSIC)
        assertTrue(engines[0].stopped)
        player.release()
    }
}
