package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "지난 모드"를 관리한다. 처음 한 번만 [storage]에서 읽고 이후에는 메모리 사본을 쓴다.
 * 사용자가 모드를 바꿀 때마다(off 포함) [record]로 저장한다.
 * 앱을 켤 때 DB를 off로 되돌리는 것은 사용자가 고른 것이 아니므로 여기에 기록하지 않는다.
 */
class LastSoundMode(private val storage: LastSoundModeStorage) {
    private val mutex = Mutex()
    private var loaded = false
    private var cached: SoundMode? = null

    /** 지난 모드. 저장된 적이 없으면 null. */
    suspend fun get(): SoundMode? = mutex.withLock {
        if (!loaded) {
            cached = storage.load()
            loaded = true
        }
        cached
    }

    /** 사용자가 고른 모드를 지난 모드로 저장한다(off 포함). */
    suspend fun record(mode: SoundMode) {
        mutex.withLock {
            cached = mode
            loaded = true
        }
        storage.save(mode)
    }

    /** "소리 켜"로 켤 모드. */
    suspend fun modeForSoundOn(): SoundMode = SoundModePolicy.modeForSoundOn(get())
}
