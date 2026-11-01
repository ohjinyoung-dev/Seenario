package com.buzzerbeater.seenario.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * [SpeechRecognizer](ko-KR)로 계속 듣는다. 한 번 인식이 끝나거나 에러가 나면
 * [start] 상태가 유지되는 동안 자동으로 다시 듣기 시작한다. 메인 스레드에서만 사용한다.
 */
class SpeechListener(
    context: Context,
    private val onResults: (List<String>) -> Unit,
    private val onFatalError: (Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrors = 0

    var isActive = false
        private set

    private val restartRunnable = Runnable { startListeningNow() }

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start() {
        if (isActive) return
        isActive = true
        consecutiveErrors = 0
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
            }
        }
        handler.removeCallbacks(restartRunnable)
        startListeningNow()
    }

    fun stop() {
        isActive = false
        handler.removeCallbacks(restartRunnable)
        recognizer?.cancel()
    }

    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListeningNow() {
        if (!isActive) return
        try {
            recognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening 실패", e)
            scheduleRestart(1_000L)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isActive) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .filter { it.isNotBlank() }
            if (isActive && candidates.isNotEmpty()) onResults(candidates)
            // onResults 콜백에서 TTS를 시작했다면 stop()이 불려 isActive=false가 되어 재시작하지 않는다.
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            if (!isActive) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "마이크 권한 없음, 듣기 중단")
                    isActive = false
                    onFatalError(error)
                }
                // 조용해서 아무 말도 못 들은 경우: 정상 흐름이므로 바로 다시 듣는다.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(RESTART_DELAY_MS)
                else -> {
                    consecutiveErrors++
                    Log.d(TAG, "인식 에러 $error (연속 $consecutiveErrors 회)")
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) recognizer?.cancel()
                    scheduleRestart((ERROR_BACKOFF_MS * consecutiveErrors).coerceAtMost(MAX_BACKOFF_MS))
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
        const val RESTART_DELAY_MS = 300L
        const val ERROR_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 5_000L
    }
}
