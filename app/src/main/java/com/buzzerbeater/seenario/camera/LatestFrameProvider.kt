package com.buzzerbeater.seenario.camera

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 화면 밖(에이전트 요청 등)에서 가장 최근 카메라 프레임을 필요할 때 꺼내 쓰는 통로.
 * 앱이 만들어 [CameraStreamScreen]의 `latestFrames`로 넘기면, 카메라가 새 프레임을 만들 때마다 채워진다.
 *
 * - 메모리에 1장만 들고 있고 디스크에 저장하지 않는다(사진 비저장 원칙).
 * - 카메라 화면이 사라지면 비운다.
 * - 오래된 사진인지 판단이 필요하면 [CameraFrame.timestampMs](`SystemClock.elapsedRealtime()` 기준)를 보면 된다.
 * - 절전 모드에서는 카메라가 프레임을 미리 만들어 두지 않으므로 [fresh]로 받아야 한다.
 *
 * 사용 예:
 * ```
 * val frames = LatestFrameProvider()
 * CameraStreamScreen(describer, trigger = trigger, latestFrames = frames)
 * val image = frames.fresh()?.let { Base64.encodeToString(it.jpeg, Base64.NO_WRAP) }
 * ```
 */
class LatestFrameProvider(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val frame = MutableStateFlow<CameraFrame?>(null)

    /** 카메라에 새 프레임을 1장 만들어 달라고 요청한다. 카메라 화면이 채우고, 사라지면 null로 비운다. */
    @Volatile
    internal var requester: (() -> Unit)? = null

    /** 가장 최근 프레임. 카메라가 아직 준비 전이거나 카메라 화면이 사라졌으면 null. 어느 스레드에서 불러도 된다. */
    fun latest(): CameraFrame? = frame.value

    /**
     * 최근 프레임이 [maxAgeMs] 안에 만든 것이면 바로 돌려주고(일반 모드는 1초마다 채워지므로 늘 여기),
     * 아니면 카메라에 새 프레임을 요청해 [timeoutMs]까지 기다린다(절전 모드).
     * 기다려도 오지 않으면 지금 있는 프레임(없으면 null)을 돌려준다.
     */
    suspend fun fresh(maxAgeMs: Long = FRESH_MAX_AGE_MS, timeoutMs: Long = FRESH_TIMEOUT_MS): CameraFrame? {
        val current = frame.value
        if (current != null && now() - current.timestampMs <= maxAgeMs) return current
        val request = requester ?: return current
        request()
        return withTimeoutOrNull(timeoutMs) { frame.first { it != null && it !== current } } ?: frame.value
    }

    internal fun update(value: CameraFrame?) {
        frame.value = value
    }

    companion object {
        const val FRESH_MAX_AGE_MS = 1_500L
        const val FRESH_TIMEOUT_MS = 1_000L
    }
}
