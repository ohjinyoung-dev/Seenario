package com.buzzerbeater.seenario.sound

import java.io.File

/** 테스트용 재생 엔진. 실제로 소리를 내지 않고 상태만 기록한다. */
class FakeEngine : SoundEngine {
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
class FakeSource(private val cached: Set<String> = emptySet()) : SoundSource {
    val requested = mutableListOf<String>()

    override fun hasCached(soundId: String) = soundId in cached

    override suspend fun fileFor(soundId: String, url: String?): File? {
        requested += soundId
        return if (url != null || soundId in cached) File(soundId) else null
    }
}
