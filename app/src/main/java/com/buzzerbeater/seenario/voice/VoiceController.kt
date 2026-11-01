package com.buzzerbeater.seenario.voice

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

enum class VoiceStatus(val label: String) {
    IDLE("대기 중"),
    LISTENING("듣는 중"),
    SPEAKING("말하는 중"),
    NEED_PERMISSION("마이크 권한 필요"),
}

data class VoiceUiState(
    val status: VoiceStatus = VoiceStatus.IDLE,
    val lastHeard: String? = null,
)

/**
 * 마이크 권한, TTS 안내, 계속 듣기, 명령 처리를 조율한다.
 *
 * Activity의 onCreate에서 만들고 `lifecycle.addObserver(controller)`로 등록한다.
 * - onResume: 듣기 시작(처음이면 안내 멘트 후 시작)
 * - onStop: 듣기와 TTS 중단
 * - TTS가 말하는 동안에는 듣지 않는다.
 */
class VoiceController(
    private val activity: ComponentActivity,
    private val listener: VoiceCommandListener,
) : DefaultLifecycleObserver {

    var uiState by mutableStateOf(VoiceUiState())
        private set

    private val tts = TtsSpeaker(activity, ::onSpeakingChanged)
    private val speech = SpeechListener(activity, ::onSpeechResults, ::onSpeechFatalError)
    private var unavailableAnnounced = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onPermissionResult(granted) }

    override fun onCreate(owner: LifecycleOwner) {
        if (!hasPermission()) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onResume(owner: LifecycleOwner) {
        startIfReady()
    }

    override fun onStop(owner: LifecycleOwner) {
        tts.stop()
        speech.destroy()
        updateStatus(if (hasPermission()) VoiceStatus.IDLE else VoiceStatus.NEED_PERMISSION)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        speech.destroy()
        tts.shutdown()
    }

    /** 화면 탭: 권한이 없으면 다시 요청하고, 있으면 "묘사해줘"와 똑같이 처리한다. */
    fun onScreenTapped() {
        if (!hasPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Log.d(TAG, "화면 탭 → DESCRIBE")
        handleCommand(VoiceCommand.DESCRIBE)
    }

    private fun startIfReady() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (!hasPermission()) {
            updateStatus(VoiceStatus.NEED_PERMISSION)
            return
        }
        if (!greeted) {
            greeted = true
            tts.speak(GREETING)
            return
        }
        if (tts.isSpeaking) return
        if (!speech.isAvailable()) {
            Log.e(TAG, "이 기기에서 음성 인식을 사용할 수 없음")
            updateStatus(VoiceStatus.IDLE)
            if (!unavailableAnnounced) {
                unavailableAnnounced = true
                tts.speak(MSG_UNAVAILABLE)
            }
            return
        }
        speech.start()
        updateStatus(VoiceStatus.LISTENING)
    }

    private fun onPermissionResult(granted: Boolean) {
        Log.d(TAG, "마이크 권한 결과: $granted")
        if (granted) {
            startIfReady()
        } else {
            updateStatus(VoiceStatus.NEED_PERMISSION)
            tts.speak(MSG_PERMISSION_DENIED)
        }
    }

    private fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) {
            speech.stop()
            updateStatus(VoiceStatus.SPEAKING)
        } else {
            updateStatus(if (hasPermission()) VoiceStatus.IDLE else VoiceStatus.NEED_PERMISSION)
            startIfReady()
        }
    }

    private fun onSpeechResults(candidates: List<String>) {
        val command = CommandParser.parse(candidates)
        val heard = candidates.firstOrNull {
            command != VoiceCommand.UNKNOWN && CommandParser.parse(it) == command
        } ?: candidates.first()
        Log.d(TAG, "heard=\"$heard\" command=$command candidates=$candidates")
        uiState = uiState.copy(lastHeard = heard)
        handleCommand(command)
    }

    private fun onSpeechFatalError(error: Int) {
        updateStatus(VoiceStatus.NEED_PERMISSION)
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
            // 계속 듣고 있어서 생활 소음에도 결과가 나오므로, 모르는 문장에는 대답하지 않는다.
            VoiceCommand.UNKNOWN -> Unit
        }
    }

    private fun updateStatus(status: VoiceStatus) {
        if (uiState.status != status) uiState = uiState.copy(status = status)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "SEENARIO_VOICE"
        const val GREETING = "씨나리오가 준비됐어요. 풍경이 궁금하면 '묘사해줘'라고 말해 주세요."
        const val MSG_PERMISSION_DENIED =
            "음성 명령을 들으려면 마이크 권한이 필요해요. 화면을 두 번 탭하면 다시 요청할게요. " +
                "계속 거부되면 설정에서 마이크 권한을 켜 주세요."
        const val MSG_UNAVAILABLE = "이 기기에서는 음성 인식을 사용할 수 없어요. 화면을 탭해서 묘사를 요청해 주세요."

        /** 화면 회전 등으로 Activity가 다시 만들어져도 안내 멘트는 프로세스당 한 번만. */
        var greeted = false
    }
}
