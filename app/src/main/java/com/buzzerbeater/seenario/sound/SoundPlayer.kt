package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.SoundMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 에이전트 응답의 소리 지시(play_sound / stop_sound)를 실행한다. 메인 스레드에서만 부른다.
 *
 * - play_sound: [source]에서 파일을 구해(저장된 파일 재사용, 없으면 다운로드) 재생한다.
 *   같은 음원이 이미 재생 중(또는 받는 중)이면 다시 시작하지 않는다. 다른 음원이면 지금 소리는 페이드아웃한다.
 * - 시작과 끝은 [fadeMs] 동안 페이드한다. 반복이 아니면 끝나기 [fadeMs] 전에 페이드아웃한다.
 * - 볼륨은 [SoundPlaybackPolicy.effectiveVolume](최대 0.5, 없으면 0.4).
 *   [setDucking]이 켜지면 [SoundPlaybackPolicy.DUCK_RATIO]만큼으로 낮춘다.
 */
class SoundPlayer(
    private val scope: CoroutineScope,
    private val source: SoundSource,
    private val engineFactory: () -> SoundEngine,
    /** 소리가 실제로 재생되기 시작했을 때(sound_id). */
    private val onStarted: (String) -> Unit = {},
    private val fadeMs: Long = FADE_MS,
    private val duckMs: Long = DUCK_FADE_MS,
    private val stepMs: Long = STEP_MS,
    private val log: (String) -> Unit = SoundLog::d,
) {
    private class Track(val id: String, val title: String?, val engine: SoundEngine, val volume: Float, val loop: Boolean) {
        /** 페이드 배율(0~1). */
        var level = 0f
        var stopping = false
        var job: Job? = null
    }

    private var current: Track? = null
    private val fadingOut = mutableListOf<Track>()

    /** 받는 중인 음원. */
    private var pendingId: String? = null
    private var loadJob: Job? = null

    /** 낮춤 배율(1 또는 [SoundPlaybackPolicy.DUCK_RATIO] 쪽으로 움직인다). */
    private var duckFactor = 1f
    private var ducking = false
    private var duckJob: Job? = null

    /** 지금 재생 중(멈추는 중 제외)이거나 받는 중인 음원 id. */
    val activeSoundId: String?
        get() = pendingId ?: current?.id

    /** 에이전트 action 하나를 실행한다. */
    fun handle(action: AgentAction) {
        when (action.type) {
            AgentAction.PLAY_SOUND -> play(action)
            AgentAction.STOP_SOUND -> stop(fade = true, reason = "stop_sound")
            else -> log("모르는 action 무시: ${action.type}")
        }
    }

    /**
     * 이 play_sound가 실제로 재생될 수 있으면 true(안전 안내를 붙일지 정할 때 쓴다).
     * 같은 음원이 재생 중이거나, sound_id가 없거나, url도 저장된 파일도 없으면 false.
     */
    fun canStart(action: AgentAction): Boolean {
        val id = action.soundId ?: return false
        if (SoundPlaybackPolicy.isDuplicate(activeSoundId, id) || current?.id == id) return false
        return !action.url.isNullOrBlank() || source.hasCached(id)
    }

    /** play_sound를 실행한다. 시작하려고 하면 true. */
    fun play(action: AgentAction): Boolean {
        val id = action.soundId
        if (id.isNullOrBlank()) {
            log("재생 안 함: sound_id 없음 (title=${action.title})")
            return false
        }
        if (SoundPlaybackPolicy.isDuplicate(activeSoundId, id)) {
            log("같은 음원이 이미 재생 중이라 다시 시작하지 않음 id=$id")
            return false
        }
        if (pendingId != null && current?.id == id) {
            // 다른 음원을 받는 중에 지금 재생 중인 음원을 다시 요청하면, 받기를 취소하고 지금 소리를 유지한다.
            loadJob?.cancel()
            pendingId = null
            log("받던 음원 취소, 재생 중인 음원 유지 id=$id")
            return false
        }
        if (action.url.isNullOrBlank() && !source.hasCached(id)) {
            log("재생 안 함: url 없음, 저장된 파일도 없음 id=$id")
            return false
        }
        val volume = SoundPlaybackPolicy.effectiveVolume(action.volume)
        val loop = action.loopable == true
        loadJob?.cancel()
        pendingId = id
        loadJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val file = source.fileFor(id, action.url)
            if (pendingId != id) return@launch
            pendingId = null
            if (file == null) {
                log("재생 안 함: 파일을 구하지 못함 id=$id")
                return@launch
            }
            current?.let { fadeOutAndRelease(it, fade = true) }
            current = null
            val engine = engineFactory()
            var created: Track? = null
            val started = engine.start(file, loop) { created?.let(::onTrackCompleted) }
            if (!started) {
                log("재생 실패 id=$id")
                return@launch
            }
            val track = Track(id, action.title, engine, volume, loop)
            created = track
            current = track
            apply(track)
            log("재생 시작 id=$id, title=${action.title}, volume=$volume, loop=$loop")
            onStarted(id)
            track.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { fadeIn(track) }
        }
        return true
    }

    /** 지금 소리를 멈춘다. [fade]면 [fadeMs] 동안 줄이고 멈춘다. 받는 중인 음원도 취소한다. */
    fun stop(fade: Boolean, reason: String) {
        val hadPending = pendingId != null
        loadJob?.cancel()
        loadJob = null
        pendingId = null
        val track = current
        current = null
        if (track == null) {
            if (hadPending) log("받는 중이던 음원 취소 ($reason)")
            return
        }
        log("정지 id=${track.id}, fade=$fade ($reason)")
        fadeOutAndRelease(track, fade)
    }

    /** 소리 모드가 바뀌었을 때. 기본 모드나 다른 소리 모드로 바뀌면 페이드로 멈춘다. */
    fun onSoundModeChanged(old: SoundMode, new: SoundMode) {
        if (SoundPlaybackPolicy.onModeChange(old, new) == SoundPlaybackPolicy.ModeChangeAction.STOP) {
            stop(fade = true, reason = "모드 변경 ${old.wire} → ${new.wire}")
        }
    }

    /** TTS나 TalkBack이 말하는 동안 true. 볼륨을 부드럽게 낮추거나 되돌린다. */
    fun setDucking(on: Boolean) {
        if (ducking == on) return
        ducking = on
        if (current != null || fadingOut.isNotEmpty()) log(if (on) "볼륨 낮춤(말하는 중)" else "볼륨 복귀")
        val from = duckFactor
        val to = SoundPlaybackPolicy.duckedVolume(1f, on)
        duckJob?.cancel()
        duckJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ramp(from, to, duckMs) { value ->
                duckFactor = value
                applyAll()
            }
        }
    }

    /** 모든 소리를 바로 멈추고 자원을 놓는다. */
    fun release() {
        stop(fade = false, reason = "release")
        fadingOut.toList().forEach(::releaseNow)
        duckJob?.cancel()
    }

    private suspend fun fadeIn(track: Track) {
        ramp(track.level, 1f, fadeMs) { value ->
            track.level = value
            apply(track)
        }
        if (track.loop) return
        // 반복이 아니면 끝나기 fadeMs 전에 페이드아웃한다.
        val duration = track.engine.durationMs
        val position = track.engine.positionMs
        if (duration <= 0 || position < 0) return
        val wait = duration - position - fadeMs
        if (wait > 0) delay(wait)
        if (current === track) {
            current = null
            log("곡 끝 → 페이드아웃 id=${track.id}")
            fadeOutAndRelease(track, fade = true)
        }
    }

    private fun fadeOutAndRelease(track: Track, fade: Boolean) {
        if (track.stopping) return
        track.stopping = true
        track.job?.cancel()
        if (!fade || fadeMs <= 0) {
            releaseNow(track)
            return
        }
        fadingOut += track
        track.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ramp(track.level, 0f, fadeMs) { value ->
                track.level = value
                apply(track)
            }
            releaseNow(track)
        }
    }

    private fun releaseNow(track: Track) {
        track.stopping = true
        track.job?.cancel()
        track.engine.stop()
        fadingOut -= track
        if (current === track) current = null
    }

    private fun onTrackCompleted(track: Track) {
        if (current === track) {
            current = null
            log("재생 끝 id=${track.id}")
        }
        releaseNow(track)
    }

    private fun applyAll() {
        current?.let(::apply)
        fadingOut.forEach(::apply)
    }

    private fun apply(track: Track) {
        track.engine.setVolume(track.volume * track.level * duckFactor)
    }

    /** [from]에서 [to]까지 [durationMs] 동안 [stepMs]마다 값을 바꾼다. */
    private suspend fun ramp(from: Float, to: Float, durationMs: Long, set: (Float) -> Unit) {
        if (durationMs <= 0 || stepMs <= 0) {
            set(to)
            return
        }
        val steps = max(1L, durationMs / stepMs).toInt()
        for (i in 1..steps) {
            delay(stepMs)
            set(from + (to - from) * i / steps)
        }
    }

    companion object {
        const val FADE_MS = 2_000L
        const val DUCK_FADE_MS = 300L
        const val STEP_MS = 50L
    }
}
