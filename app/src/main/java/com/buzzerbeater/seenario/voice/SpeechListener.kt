package com.buzzerbeater.seenario.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * [SpeechRecognizer](ko-KR)로 **한 번만** 듣는다(눌러서 말하기).
 *
 * [listenOnce]를 부르면 시스템 시작음이 한 번 나고, 결과나 에러가 한 번 오면 듣기가 끝난다.
 * 자동으로 다시 듣지 않는다. 메인 스레드에서만 사용한다.
 */
class SpeechListener(
    context: Context,
    private val onResults: (List<String>) -> Unit,
    private val onNoSpeech: () -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    var isListening = false
        private set

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** 한 번 듣기 시작한다. 이미 듣는 중이면 무시한다. 시작에 실패하면 [onError]로 알린다. */
    fun listenOnce() {
        if (isListening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        isListening = true
        try {
            r.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening 실패", e)
            isListening = false
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** 듣기를 취소한다. 취소 뒤에는 콜백을 부르지 않는다. */
    fun cancel() {
        isListening = false
        recognizer?.cancel()
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            // 취소 뒤 늦게 도착한 결과는 버린다.
            if (!isListening) return
            isListening = false
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .filter { it.isNotBlank() }
            if (candidates.isEmpty()) onNoSpeech() else onResults(candidates)
        }

        override fun onError(error: Int) {
            if (!isListening) return
            isListening = false
            when (error) {
                // 아무 말도 못 들은 경우: 조용히 끝낸다.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onNoSpeech()
                else -> {
                    Log.d(TAG, "인식 에러 $error")
                    onError(error)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "SEENARIO_VOICE"
    }
}
