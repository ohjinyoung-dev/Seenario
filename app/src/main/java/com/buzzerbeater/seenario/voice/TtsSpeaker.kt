package com.buzzerbeater.seenario.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * [TextToSpeech] 래퍼. 말하기 시작/종료를 메인 스레드에서 [onSpeakingChanged]로 알린다.
 *
 * [speak]를 부르는 즉시 "말하는 중"으로 바뀐다. 그래야 음성 인식이 TTS 시작 전에
 * 재시작되어 자기 목소리를 듣는 일이 없다. 새 문장은 이전 문장을 끊고 말한다.
 *
 * TalkBack이 말하는 중이면 끝날 때까지(최대 [MAX_TALKBACK_WAIT_MS]) 기다렸다가 말해서 두 목소리가 겹치지 않게 한다.
 * 예: 앱 아이콘을 두 번 탭해 실행하면 TalkBack이 아이콘 이름을 읽는 중에 시작 인사가 나오는 경우.
 */
class TtsSpeaker(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val talkBack = TalkBackSpeechMonitor(context.applicationContext, mainHandler)
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var failed = false
    private var utteranceSeq = 0
    private var currentUtteranceId: String? = null

    /** 대기 중인 문장을 언제부터 기다렸는지. TalkBack을 무한정 기다리지 않기 위해 쓴다. */
    private var waitStartedAt = 0L
    private var interruptedTalkBack = false
    private val flushPendingRunnable = Runnable { flushPending() }

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
        // 이전 문장은 바로 끊는다. 끊긴 문장의 종료 콜백이 상태를 바꾸지 않도록 id를 먼저 지운다.
        if (ready && currentUtteranceId != null) {
            currentUtteranceId = null
            tts.stop()
        }
        pending.clear()
        pending.addLast(text)
        waitStartedAt = SystemClock.uptimeMillis()
        interruptedTalkBack = false
        mainHandler.removeCallbacks(flushPendingRunnable)
        flushPending()
    }

    fun stop() {
        pending.clear()
        mainHandler.removeCallbacks(flushPendingRunnable)
        currentUtteranceId = null
        if (ready) tts.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        talkBack.release()
        tts.shutdown()
    }

    /** 엔진이 준비됐고 TalkBack이 조용하면(또는 너무 오래 기다렸으면) 대기 중인 문장을 말한다. */
    private fun flushPending() {
        if (!ready || pending.isEmpty()) return
        val waited = SystemClock.uptimeMillis() - waitStartedAt
        if (waited < MAX_TALKBACK_WAIT_MS && talkBack.isSpeaking(TALKBACK_QUIET_MS)) {
            if (!interruptedTalkBack) {
                Log.d(TAG, "TalkBack이 말하는 중 → 멈춤 요청")
                talkBack.interrupt()
                interruptedTalkBack = true
            }
            mainHandler.postDelayed(flushPendingRunnable, TALKBACK_POLL_MS)
            return
        }
        if (waited >= TALKBACK_POLL_MS) Log.d(TAG, "TalkBack이 말을 마칠 때까지 ${waited}ms 기다림")
        val text = pending.removeLast()
        pending.clear()
        speakNow(text)
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
        flushPending()
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

        /** TalkBack이 이만큼 조용하면 말을 마쳤다고 본다(문장 사이 쉼보다 길게). */
        const val TALKBACK_QUIET_MS = 400L
        const val TALKBACK_POLL_MS = 100L
        /** TalkBack이 계속 말해도 이만큼 기다린 뒤에는 말한다. */
        const val MAX_TALKBACK_WAIT_MS = 5_000L
    }
}
