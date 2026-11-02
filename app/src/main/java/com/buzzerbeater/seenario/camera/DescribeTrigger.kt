package com.buzzerbeater.seenario.camera

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 화면 밖(음성 명령 등)에서 "지금 풍경 묘사해줘"를 요청하는 통로.
 *
 * 음성 모듈과 연결 예시(MainActivity):
 * ```
 * val trigger = DescribeTrigger()
 * voiceController = VoiceController(this) { trigger.request() }
 * CameraStreamScreen(describer, trigger = trigger, onDescribed = { /* TTS로 읽기 */ })
 * ```
 */
class DescribeTrigger {
    private val _requests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    /** 어느 스레드에서 불러도 된다. 묘사 중이면 무시된다. */
    fun request() {
        _requests.tryEmit(Unit)
    }
}
