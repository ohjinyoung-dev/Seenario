package com.buzzerbeater.seenario.sound

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** 음원 하나를 재생하는 엔진. 하나의 엔진은 한 곡만 재생하고, [stop] 뒤에는 다시 쓰지 않는다. */
interface SoundEngine {
    /** [file]을 볼륨 0으로 재생하기 시작한다. 실패하면 false. 반복이 아니면 끝났을 때 [onCompleted]를 부른다. */
    fun start(file: File, loop: Boolean, onCompleted: () -> Unit): Boolean

    fun setVolume(volume: Float)

    /** 전체 길이(ms). 모르면 -1. */
    val durationMs: Int

    /** 지금 위치(ms). 모르면 -1. */
    val positionMs: Int

    /** 정지하고 자원을 놓는다. */
    fun stop()
}

/**
 * android [MediaPlayer]로 재생한다. 사용자가 듣던 다른 음악을 멈추지 않도록 오디오 포커스는 요청하지 않는다.
 * 기기에 저장된 파일만 재생하므로 준비([MediaPlayer.prepare])는 메인 스레드에서 바로 한다.
 */
class MediaPlayerSoundEngine : SoundEngine {
    private var player: MediaPlayer? = null

    override fun start(file: File, loop: Boolean, onCompleted: () -> Unit): Boolean {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setDataSource(file.path)
            p.isLooping = loop
            p.setVolume(0f, 0f)
            p.setOnCompletionListener { onCompleted() }
            p.setOnErrorListener { _, what, extra ->
                SoundLog.d("재생 오류 what=$what extra=$extra")
                onCompleted()
                true
            }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            SoundLog.d("재생 시작 실패: ${e.javaClass.simpleName}")
            p.release()
            false
        }
    }

    override fun setVolume(volume: Float) {
        player?.setVolume(volume, volume)
    }

    override val durationMs: Int
        get() = runCatching { player?.duration }.getOrNull() ?: -1

    override val positionMs: Int
        get() = runCatching { player?.currentPosition }.getOrNull() ?: -1

    override fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
    }
}
