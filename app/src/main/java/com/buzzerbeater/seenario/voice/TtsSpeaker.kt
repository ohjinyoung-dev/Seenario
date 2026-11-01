package com.buzzerbeater.seenario.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * [TextToSpeech] 래퍼. 말하기 시작/종료를 메인 스레드에서 [onSpeakingChanged]로 알린다.
 *
 * [speak]를 부르는 즉시 "말하는 중"으로 바뀐다. 그래야 음성 인식이 TTS 시작 전에
 * 재시작되어 자기 목소리를 듣는 일이 없다. 새 문장은 이전 문장을 끊고(QUEUE_FLUSH) 말한다.
 */
class TtsSpeaker(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var failed = false
    private var utteranceSeq = 0
    private var currentUtteranceId: String? = null

    var isSpeaking = false
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        mainHandler.post { onInit(status) }
    }

    fun speak(text: String) {
        if (failed) {
            Log.w(TAG, "TTS 사용 불가, 말하지 못함: $text")
            return
        }
        setSpeaking(true)
        if (!ready) {
            pending.clear()
            pending.addLast(text)
            return
        }
        speakNow(text)
    }

    fun stop() {
        pending.clear()
        currentUtteranceId = null
        if (ready) tts.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS 초기화 실패: $status")
            failed = true
            pending.clear()
            setSpeaking(false)
            return
        }
        val langResult = tts.setLanguage(Locale.KOREAN)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "한국어 TTS를 사용할 수 없음: $langResult")
        }
        tts.setOnUtteranceProgressListener(progressListener)
        ready = true
        pending.removeLastOrNull()?.let { speakNow(it) }
        pending.clear()
    }

    private fun speakNow(text: String) {
        val id = "seenario-${++utteranceSeq}"
        currentUtteranceId = id
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak 실패: $result")
            currentUtteranceId = null
            setSpeaking(false)
        }
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        mainHandler.post {
            if (utteranceId != null && utteranceId == currentUtteranceId) {
                currentUtteranceId = null
                setSpeaking(false)
            }
        }
    }

    private fun setSpeaking(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
        onSpeakingChanged(speaking)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceFinished(utteranceId)
    }

    private companion object {
        const val TAG = "SEENARIO_VOICE"
    }
}
