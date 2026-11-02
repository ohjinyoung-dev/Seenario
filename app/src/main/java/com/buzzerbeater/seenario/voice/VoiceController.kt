package com.buzzerbeater.seenario.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

enum class VoiceStatus(val label: String) {
    IDLE("대기 중"),
    LISTENING("듣는 중"),
    SPEAKING("말하는 중"),
}

data class VoiceUiState(
    val status: VoiceStatus = VoiceStatus.IDLE,
    val lastHeard: String? = null,
    val hasMicPermission: Boolean = true,
    /** false면 음성 인식을 못 쓰는 기기라서, 누르면 바로 묘사를 요청한다. */
    val speechAvailable: Boolean = true,
)

/**
 * 눌러서 말하기(push-to-talk) 방식으로 음성 명령을 처리한다.
 *
 * 평소에는 듣지 않고, [onTriggerPressed]가 불릴 때만 한 번 듣는다.
 * 트리거: 화면 탭, 볼륨 아래 버튼, 이어폰/헤드셋의 재생·일시정지 버튼.
 *
 * Activity의 onCreate에서 만들고 `lifecycle.addObserver(controller)`로 등록한 뒤,
 * Activity의 onKeyDown/onKeyUp에서 [handleKeyDown]/[handleKeyUp]을 불러 준다.
 */
class VoiceController(
    private val activity: ComponentActivity,
    private val listener: VoiceCommandListener,
) : DefaultLifecycleObserver {

    private val tts = TtsSpeaker(activity, ::onSpeakingChanged)
    private val speech = SpeechListener(activity, ::onSpeechResults, ::onNoSpeech, ::onSpeechError)

    /** 음성 인식 서비스가 없는 기기면 false. 이때는 누르면 바로 묘사를 요청한다(대체 동작). */
    private val speechAvailable = speech.isAvailable().also {
        if (!it) Log.w(TAG, "이 기기에서 음성 인식을 사용할 수 없음 → 탭하면 바로 묘사 요청")
    }

    var uiState by mutableStateOf(
        VoiceUiState(hasMicPermission = hasPermission(), speechAvailable = speechAvailable),
    )
        private set
    private var mediaSession: MediaSession? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onPermissionResult(granted) }

    override fun onCreate(owner: LifecycleOwner) {
        // 음성 인식을 못 쓰면 마이크 권한도 필요 없다.
        if (speechAvailable && !hasPermission()) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStart(owner: LifecycleOwner) {
        startMediaSession()
    }

    override fun onResume(owner: LifecycleOwner) {
        refreshPermission()
        greetIfNeeded()
    }

    override fun onStop(owner: LifecycleOwner) {
        speech.destroy()
        tts.stop()
        releaseMediaSession()
        updateStatus(VoiceStatus.IDLE)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        speech.destroy()
        tts.shutdown()
        releaseMediaSession()
    }

    /**
     * 화면 탭, 볼륨 아래 버튼, 헤드셋 버튼이 모두 여기로 온다. 한 번 듣기를 시작한다.
     * 음성 인식을 못 쓰는 기기에서는 대신 바로 묘사를 요청한다.
     */
    fun onTriggerPressed() {
        if (!speechAvailable) {
            Log.d(TAG, "음성 인식 미지원 → 바로 DESCRIBE")
            handleCommand(VoiceCommand.DESCRIBE)
            return
        }
        if (!hasPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (speech.isListening) return
        // 말하는 중에 누르면 말을 끊고 바로 듣는다.
        if (tts.isSpeaking) tts.stop()
        Log.d(TAG, "듣기 시작")
        speech.listenOnce()
        if (speech.isListening) updateStatus(VoiceStatus.LISTENING)
    }

    /** 트리거 키면 소비(true)한다. 볼륨 아래 버튼을 소비하므로 볼륨이 바뀌지 않는다. */
    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in TRIGGER_KEYS) return false
        if (event.repeatCount == 0) onTriggerPressed()
        return true
    }

    /** 짝이 되는 key up도 소비해서 시스템이나 다른 미디어 앱으로 넘어가지 않게 한다. */
    fun handleKeyUp(keyCode: Int): Boolean = keyCode in TRIGGER_KEYS

    private fun greetIfNeeded() {
        if (greeted) return
        // 음성 인식을 쓰는 기기는 마이크 권한을 받은 뒤에 안내한다.
        if (speechAvailable && !hasPermission()) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        greeted = true
        tts.speak(if (speechAvailable) GREETING else GREETING_NO_SPEECH)
    }

    private fun onPermissionResult(granted: Boolean) {
        Log.d(TAG, "마이크 권한 결과: $granted")
        refreshPermission()
        if (granted) greetIfNeeded() else tts.speak(MSG_PERMISSION_DENIED)
    }

    private fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) {
            speech.cancel()
            updateStatus(VoiceStatus.SPEAKING)
        } else if (!speech.isListening) {
            updateStatus(VoiceStatus.IDLE)
        }
    }

    private fun onSpeechResults(candidates: List<String>) {
        val command = CommandParser.parse(candidates)
        val heard = candidates.firstOrNull {
            command != VoiceCommand.UNKNOWN && CommandParser.parse(it) == command
        } ?: candidates.first()
        Log.d(TAG, "heard=\"$heard\" command=$command candidates=$candidates")
        uiState = uiState.copy(lastHeard = heard)
        updateStatus(VoiceStatus.IDLE)
        handleCommand(command)
    }

    private fun onNoSpeech() {
        Log.d(TAG, "아무 말 없음, 듣기 종료")
        updateStatus(VoiceStatus.IDLE)
    }

    private fun onSpeechError(error: Int) {
        updateStatus(VoiceStatus.IDLE)
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            refreshPermission()
        } else {
            tts.speak(MSG_LISTEN_FAILED)
        }
    }

    private fun handleCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.DESCRIBE -> {
                tts.speak("묘사 명령을 받았어요")
                listener.onDescribeRequested()
            }
            VoiceCommand.SOUND_ON -> tts.speak("배경 소리 켜기 명령을 받았어요")
            VoiceCommand.SOUND_OFF -> tts.speak("배경 소리 끄기 명령을 받았어요")
            VoiceCommand.SAVE -> tts.speak("저장 명령을 받았어요")
            VoiceCommand.REPEAT -> tts.speak("다시 듣기 명령을 받았어요")
            VoiceCommand.UNKNOWN -> Unit
        }
    }

    /**
     * 블루투스 헤드셋 버튼은 Activity의 onKeyDown으로 오지 않을 수 있어서,
     * 앱이 화면에 있는 동안(onStart~onStop)만 미디어 세션으로도 받는다.
     */
    private fun startMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(activity, "SeenarioVoice").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(
                        mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java,
                    ) ?: return false
                    if (event.keyCode !in TRIGGER_KEYS) return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        Log.d(TAG, "미디어 버튼 ${event.keyCode}")
                        onTriggerPressed()
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                    .build(),
            )
            isActive = true
        }
    }

    private fun releaseMediaSession() {
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
    }

    private fun refreshPermission() {
        val granted = hasPermission()
        if (uiState.hasMicPermission != granted) uiState = uiState.copy(hasMicPermission = granted)
    }

    private fun updateStatus(status: VoiceStatus) {
        if (uiState.status != status) uiState = uiState.copy(status = status)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "SEENARIO_VOICE"
        const val GREETING =
            "씨나리오가 준비됐어요. 화면을 누르거나 볼륨 아래 버튼을 누르고 '묘사해줘'라고 말해 주세요."
        const val MSG_PERMISSION_DENIED =
            "음성 명령을 들으려면 마이크 권한이 필요해요. 화면을 두 번 탭하면 다시 요청할게요. " +
                "계속 거부되면 설정에서 마이크 권한을 켜 주세요."
        const val GREETING_NO_SPEECH = "화면을 누르면 주변을 묘사해 드려요."
        const val MSG_LISTEN_FAILED = "잘 듣지 못했어요. 다시 눌러 말해 주세요."

        val TRIGGER_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )

        /** 화면 회전 등으로 Activity가 다시 만들어져도 안내 멘트는 프로세스당 한 번만. */
        var greeted = false
    }
}
