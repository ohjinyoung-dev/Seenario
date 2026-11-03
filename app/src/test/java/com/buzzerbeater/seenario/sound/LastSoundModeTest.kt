package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastSoundModeTest {

    /** 휴대폰 저장소 대신 쓰는 메모리 저장소. */
    private class FakeStorage(var value: SoundMode? = null) : LastSoundModeStorage {
        val saved = mutableListOf<SoundMode>()
        var loadCount = 0

        override suspend fun load(): SoundMode? {
            loadCount++
            return value
        }

        override suspend fun save(mode: SoundMode) {
            saved += mode
            value = mode
        }
    }

    @Test
    fun record_savesEveryChange_includingOff() = runBlocking {
        val storage = FakeStorage()
        val last = LastSoundMode(storage)
        last.record(SoundMode.AMBIENT)
        last.record(SoundMode.OFF)
        last.record(SoundMode.MUSIC)
        last.record(SoundMode.OFF)
        assertEquals(listOf(SoundMode.AMBIENT, SoundMode.OFF, SoundMode.MUSIC, SoundMode.OFF), storage.saved)
        assertEquals(SoundMode.OFF, storage.value)
        assertEquals(SoundMode.OFF, last.get())
    }

    @Test
    fun get_readsStorageOnlyOnce() = runBlocking {
        val storage = FakeStorage(SoundMode.MUSIC)
        val last = LastSoundMode(storage)
        assertEquals(SoundMode.MUSIC, last.get())
        assertEquals(SoundMode.MUSIC, last.get())
        assertEquals(1, storage.loadCount)
    }

    @Test
    fun get_nothingSaved_isNull() = runBlocking {
        assertNull(LastSoundMode(FakeStorage()).get())
    }

    @Test
    fun soundOn_usesLastAmbientOrMusic() = runBlocking {
        assertEquals(SoundMode.AMBIENT, LastSoundMode(FakeStorage(SoundMode.AMBIENT)).modeForSoundOn())
        assertEquals(SoundMode.MUSIC, LastSoundMode(FakeStorage(SoundMode.MUSIC)).modeForSoundOn())
    }

    @Test
    fun soundOn_offOrNothing_isAmbient() = runBlocking {
        assertEquals(SoundMode.AMBIENT, LastSoundMode(FakeStorage(SoundMode.OFF)).modeForSoundOn())
        assertEquals(SoundMode.AMBIENT, LastSoundMode(FakeStorage(null)).modeForSoundOn())
    }

    @Test
    fun soundOn_followsLatestRecord() = runBlocking {
        val last = LastSoundMode(FakeStorage(SoundMode.AMBIENT))
        last.record(SoundMode.MUSIC)
        assertEquals(SoundMode.MUSIC, last.modeForSoundOn())
        // 배경음을 쓰다가 기본 모드로 바꾸면 지난 모드는 off → "소리 켜"는 환경음.
        last.record(SoundMode.OFF)
        assertEquals(SoundMode.AMBIENT, last.modeForSoundOn())
    }
}
