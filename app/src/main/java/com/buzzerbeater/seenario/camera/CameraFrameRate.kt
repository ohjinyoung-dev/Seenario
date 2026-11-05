package com.buzzerbeater.seenario.camera

/**
 * 카메라 프레임 속도 고르기(안드로이드에 의존하지 않아 단위 테스트할 수 있다).
 *
 * 앱은 요청할 때 사진 1장만 쓰므로 초당 30프레임이 필요 없다. 카메라 센서와 이미지 처리 일을 줄이려고
 * [TARGET_FPS](15) 근처로 낮춘다. 미리보기가 시연에서 너무 끊겨 보이지 않도록 [MIN_FPS](10)보다 낮게는 고르지 않는다.
 * 기기마다 지원하는 범위가 달라서(예: S21+는 고정 15/20/24/30, 가변 8~30/10~30 등) 그 기기 목록에서 고른다.
 */
object CameraFrameRate {
    const val TARGET_FPS = 15
    const val MIN_FPS = 10

    /**
     * 지원 범위 목록([lower]~[upper] 쌍)에서 고른다. 고를 것이 없으면 null(기본값 그대로 둠).
     * 1. 고정 속도(lower == upper) 중 [MIN_FPS]~[TARGET_FPS] 사이에서 가장 큰 것
     * 2. 없으면 최댓값이 [MIN_FPS] 이상인 범위 중 최댓값이 가장 작은 것(같으면 최솟값이 큰 것)
     *    가변 범위(예: 8~30)는 밝은 곳에서 최댓값으로 돌기 때문에 최댓값을 기준으로 본다.
     */
    fun pick(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? {
        ranges.filter { (lo, hi) -> lo == hi && hi in MIN_FPS..TARGET_FPS }
            .maxByOrNull { it.second }
            ?.let { return it }
        return ranges.filter { (_, hi) -> hi >= MIN_FPS }
            .minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
    }
}
