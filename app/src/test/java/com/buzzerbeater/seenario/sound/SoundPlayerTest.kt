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
import java.io.File

class SoundPlayerTest {

    private class FakeEngine : SoundEngine {
        var started = false
        var stopped = false
        var lastVolume = -1f

        override fun start(file: File, loop: Boolean, onCompleted: () -> Unit): Boolean {
            started = true
            return true
        }

        override fun setVolume(volume: Float) {
            lastVolume = volume
        }

        override val durationMs: Int = -1
        override val positionMs: Int = -1

        override fun stop() {
            stopped = true
        }
    }

    /** 다운로드 대신 바로 파일을 돌려준다. url도 저장된 파일도 없으면 null. */
    private class FakeSource(private val cached: Set<String> = emptySet()) : SoundSource {
        val requested = mutableListOf<String>()

        override fun hasCached(soundId: String) = soundId in cached

        override suspend fun fileFor(soundId: String, url: String?): File? {
            requested += soundId
            return if (url != null || soundId in cached) File(soundId) else null
        }
    }

    private val engines = mutableListOf<FakeEngine>()

    /** 페이드 시간을 0으로 두어 바로 끝나게 한다. */
    private fun player(scope: CoroutineScope, source: SoundSource = FakeSource()) = SoundPlayer(
        scope = scope,
        source = source,
        engineFactory = { FakeEngine().also { engines += it } },
        fadeMs = 0,
        duckMs = 0,
        log = {},
    )

    private fun play(id: String, url: String? = "https://example.invalid/$id", volume: Double? = null) =
        AgentAction(type = AgentAction.PLAY_SOUND, soundId = id, title = id, url = url, volume = volume, loopable = true)

    @Test
    fun samePlayTwice_startsOnlyOnce() = runBlocking {
        val p = player(this)
        assertTrue(p.play(play("rain")))
        assertFalse(p.play(play("rain")))
        assertEquals(1, engines.size)
        assertFalse(engines[0].stopped)
        assertEquals("rain", p.activeSoundId)
        p.release()
    }

    @Test
    fun differentSound_replacesCurrent() = runBlocking {
        val p = player(this)
        p.play(play("rain"))
        p.play(play("piano"))
        assertEquals(2, engines.size)
        assertTrue(engines[0].stopped)
        assertFalse(engines[1].stopped)
        assertEquals("piano", p.activeSoundId)
        p.release()
    }

    @Test
    fun volume_isCappedAndDucked() = runBlocking {
        val p = player(this)
        p.play(play("rain", volume = 0.9))
        assertEquals(0.5f, engines[0].lastVolume, 0.0001f)
        p.setDucking(true)
        assertEquals(0.15f, engines[0].lastVolume, 0.0001f)
        p.setDucking(false)
        assertEquals(0.5f, engines[0].lastVolume, 0.0001f)
        p.release()
    }

    @Test
    fun switchingToDefaultMode_stopsSound() = runBlocking {
        val p = player(this)
        p.play(play("rain"))
        p.onSoundModeChanged(SoundMode.AMBIENT, SoundMode.OFF)
        assertTrue(engines[0].stopped)
        assertNull(p.activeSoundId)
        p.release()
    }

    @Test
    fun switchingAmbientToMusic_stopsSound() = runBlocking {
        val p = player(this)
        p.play(play("rain"))
        p.onSoundModeChanged(SoundMode.AMBIENT, SoundMode.MUSIC)
        assertTrue(engines[0].stopped)
        p.release()
    }

    @Test
    fun stopSoundAction_stops() = runBlocking {
        val p = player(this)
        p.play(play("rain"))
        p.handle(AgentAction(type = AgentAction.STOP_SOUND))
        assertTrue(engines[0].stopped)
        p.release()
    }

    @Test
    fun noUrlAndNotCached_doesNotPlay() = runBlocking {
        val source = FakeSource()
        val p = player(this, source)
        assertFalse(p.canStart(play("rain", url = null)))
        assertFalse(p.play(play("rain", url = null)))
        assertTrue(engines.isEmpty())
        assertTrue(source.requested.isEmpty())
        p.release()
    }

    @Test
    fun noUrlButCached_plays() = runBlocking {
        val p = player(this, FakeSource(cached = setOf("rain")))
        assertTrue(p.play(play("rain", url = null)))
        assertEquals(1, engines.size)
        p.release()
    }
}
