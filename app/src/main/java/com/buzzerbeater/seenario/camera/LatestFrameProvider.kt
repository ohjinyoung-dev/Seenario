package com.buzzerbeater.seenario.camera

/**
 * 화면 밖(에이전트 요청 등)에서 가장 최근 카메라 프레임을 필요할 때 꺼내 쓰는 통로.
 * 앱이 만들어 [CameraStreamScreen]의 `latestFrames`로 넘기면, 카메라가 새 프레임을 만들 때마다 채워진다.
 *
 * - 메모리에 1장만 들고 있고 디스크에 저장하지 않는다(사진 비저장 원칙).
 * - 카메라 화면이 사라지면 비운다.
 * - 오래된 사진인지 판단이 필요하면 [CameraFrame.timestampMs](`SystemClock.elapsedRealtime()` 기준)를 보면 된다.
 *
 * 사용 예:
 * ```
 * val frames = LatestFrameProvider()
 * CameraStreamScreen(describer, trigger = trigger, latestFrames = frames)
 * val image = frames.latest()?.let { Base64.encodeToString(it.jpeg, Base64.NO_WRAP) }
 * ```
 */
class LatestFrameProvider {
    @Volatile
    private var frame: CameraFrame? = null

    /** 가장 최근 프레임. 카메라가 아직 준비 전이거나 카메라 화면이 사라졌으면 null. 어느 스레드에서 불러도 된다. */
    fun latest(): CameraFrame? = frame

    internal fun update(value: CameraFrame?) {
        frame = value
    }
}
