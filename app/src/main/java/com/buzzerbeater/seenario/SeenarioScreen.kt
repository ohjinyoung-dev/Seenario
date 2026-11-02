package com.buzzerbeater.seenario

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.buzzerbeater.seenario.camera.CameraStreamScreen
import com.buzzerbeater.seenario.camera.DescribeTrigger
import com.buzzerbeater.seenario.camera.SceneDescriber
import com.buzzerbeater.seenario.data.SupabaseProvider
import com.buzzerbeater.seenario.voice.VoiceCommandListener
import com.buzzerbeater.seenario.voice.VoiceController
import com.buzzerbeater.seenario.voice.VoiceStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SEENARIO"

/** 화면 위쪽에 보여 주는 상태. 음성 상태([VoiceStatus])에 "살펴보는 중"을 더했다. */
enum class SeenarioStatus(val label: String) {
    IDLE("대기 중"),
    LISTENING("듣는 중"),
    LOOKING("살펴보는 중"),
    SPEAKING("말하는 중"),
}

/**
 * 음성 모듈(voice/)과 카메라 모듈(camera/)을 잇는다.
 *
 * 흐름: 화면 탭/볼륨 아래 버튼 → 듣기 → "묘사해줘" → [DescribeTrigger.request] → 카메라 캡처
 * → Gemini 묘사 → 아래쪽에 텍스트 표시 + TTS 낭독 → 대기 중.
 *
 * 카메라·마이크 권한도 여기서 한 번에 요청한다. Activity의 onCreate에서 만든다.
 */
class SeenarioController(
    private val activity: ComponentActivity,
) : VoiceCommandListener, DefaultLifecycleObserver {

    val voice = VoiceController(activity, this, requestPermissions = ::requestPermissions)
    val trigger = DescribeTrigger()
    val describer = SceneDescriber(SupabaseProvider.client)

    /** 묘사를 요청해 결과를 기다리는 중. */
    var describing by mutableStateOf(false)
        private set

    /** 아래쪽에 보여 줄 텍스트. null이면 사용 안내를 보여 준다. */
    var resultText by mutableStateOf<String?>(null)
        private set

    /** [resultText]를 TTS가 읽었는지. 읽었다면 TalkBack이 또 읽지 않도록 liveRegion을 끈다. */
    var resultNarrated by mutableStateOf(false)
        private set

    /** 아직 허용되지 않은 권한. 카메라 권한이 있어야 미리보기를 띄운다. */
    var missingPermissions by mutableStateOf(findMissingPermissions())
        private set

    val status: SeenarioStatus
        get() = when {
            voice.uiState.status == VoiceStatus.LISTENING -> SeenarioStatus.LISTENING
            describing -> SeenarioStatus.LOOKING
            voice.uiState.status == VoiceStatus.SPEAKING -> SeenarioStatus.SPEAKING
            else -> SeenarioStatus.IDLE
        }

    private var lastDescription: String? = null
    private var describeWatchdog: Job? = null
    private var requestingPermissions = false
    private var permanentlyDenied = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        ::onPermissionResult,
    )

    init {
        // VoiceController를 먼저 등록해서 onResume 순서가 음성 → 화면 조립이 되게 한다.
        activity.lifecycle.addObserver(voice)
        activity.lifecycle.addObserver(this)
    }

    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean = voice.handleKeyDown(keyCode, event)

    fun handleKeyUp(keyCode: Int): Boolean = voice.handleKeyUp(keyCode)

    override fun onResume(owner: LifecycleOwner) {
        refreshPermissions()
        if (!startupPermissionAsked) {
            startupPermissionAsked = true
            requestPermissions()
        }
    }

    override fun onDescribeRequested() {
        if (describing) {
            voice.speak(MSG_BUSY)
            return
        }
        refreshPermissions()
        if (Manifest.permission.CAMERA in missingPermissions) {
            requestPermissions()
            return
        }
        describing = true
        show(TEXT_LOOKING, speech = MSG_LOOKING)
        trigger.request()
        // 카메라 화면이 아직 요청을 받을 수 없는 상태였다면 결과가 오지 않으므로, 오래 걸리면 실패로 끝낸다.
        describeWatchdog = activity.lifecycleScope.launch {
            delay(DESCRIBE_TIMEOUT_MS)
            Log.w(TAG, "Describe timed out")
            onDescribeFailed()
        }
    }

    override fun onRepeatRequested() {
        val last = lastDescription
        when {
            last == null -> voice.speak(MSG_NOTHING_TO_REPEAT)
            describing -> voice.speak(last)
            else -> show(last)
        }
    }

    fun onDescribed(description: String) {
        if (!describing) return
        finishDescribing()
        lastDescription = description
        show(description)
    }

    fun onDescribeFailed() {
        if (!describing) return
        finishDescribing()
        show(MSG_ERROR)
    }

    private fun finishDescribing() {
        describing = false
        describeWatchdog?.cancel()
        describeWatchdog = null
    }

    private fun show(text: String, speech: String = text) {
        resultText = text
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.speak(speech)
        // TTS를 쓸 수 없는 기기에서는 SPEAKING이 되지 않으므로 TalkBack이 대신 읽게 둔다.
        resultNarrated = voice.uiState.status == VoiceStatus.SPEAKING
    }

    /** 빠진 권한을 TTS로 먼저 안내한 뒤, 안내가 끝나면 한 번에 요청한다. */
    private fun requestPermissions() {
        refreshPermissions()
        val missing = missingPermissions
        if (missing.isEmpty() || requestingPermissions) return
        requestingPermissions = true
        val goToSettings = permanentlyDenied
        voice.speak(if (goToSettings) MSG_OPEN_SETTINGS else permissionNotice(missing))
        activity.lifecycleScope.launch {
            withTimeoutOrNull(SPEECH_WAIT_MS) {
                snapshotFlow { voice.uiState.status }.first { it != VoiceStatus.SPEAKING }
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                // 안내 중에 앱을 벗어났으면 다음에 돌아왔을 때 다시 묻는다.
                requestingPermissions = false
                startupPermissionAsked = false
                return@launch
            }
            if (goToSettings) {
                requestingPermissions = false
                openAppSettings()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun onPermissionResult(results: Map<String, Boolean>) {
        requestingPermissions = false
        refreshPermissions()
        val denied = results.filterValues { !it }.keys
        Log.d(TAG, "권한 결과: $results")
        if (denied.isEmpty()) return
        // 두 번 거부하면 시스템이 더 묻지 않으므로, 다음부터는 설정 화면으로 안내한다.
        permanentlyDenied = denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        // 마이크가 허용됐다면 곧 VoiceController가 시작 안내를 하므로 덮어쓰지 않는다.
        // 카메라만 거부된 경우는 묘사를 요청할 때 다시 안내한다.
        if (Manifest.permission.RECORD_AUDIO in denied || !voice.uiState.speechAvailable) {
            voice.speak(if (permanentlyDenied) MSG_DENIED_SETTINGS else MSG_DENIED_RETRY)
        }
    }

    private fun refreshPermissions() {
        val missing = findMissingPermissions()
        if (missing != missingPermissions) missingPermissions = missing
        if (missing.isEmpty()) permanentlyDenied = false
    }

    private fun findMissingPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        // 음성 인식을 못 쓰는 기기는 마이크가 필요 없다.
        if (voice.uiState.speechAvailable) add(Manifest.permission.RECORD_AUDIO)
    }.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }

    private fun openAppSettings() {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            ),
        )
    }

    private companion object {
        const val DESCRIBE_TIMEOUT_MS = 60_000L
        const val SPEECH_WAIT_MS = 15_000L

        const val MSG_LOOKING = "잠시만요, 주변을 살펴볼게요."
        const val TEXT_LOOKING = "주변을 살펴보고 있어요…"
        const val MSG_BUSY = "지금 살펴보는 중이에요"
        const val MSG_ERROR = "주변을 살펴보지 못했어요. 다시 시도해 주세요."
        const val MSG_NOTHING_TO_REPEAT = "아직 들려드린 묘사가 없어요. '묘사해줘'라고 말해 주세요."
        const val MSG_DENIED_RETRY = "권한이 있어야 씨나리오를 쓸 수 있어요. 화면을 누르면 다시 물어볼게요."
        const val MSG_DENIED_SETTINGS =
            "권한이 꺼져 있어요. 화면을 누르면 설정 화면을 열게요. 권한에서 카메라와 마이크를 허용해 주세요."
        const val MSG_OPEN_SETTINGS = "설정 화면을 열게요. 권한에서 카메라와 마이크를 허용해 주세요."

        fun permissionNotice(missing: List<String>): String {
            val names = when {
                missing.size >= 2 -> "카메라와 마이크"
                Manifest.permission.CAMERA in missing -> "카메라"
                else -> "마이크"
            }
            return "곧 $names 권한을 묻는 창이 떠요. '앱 사용 중에만 허용'을 선택해 주세요."
        }

        /** 화면 회전 등으로 Activity가 다시 만들어져도 시작할 때 권한은 프로세스당 한 번만 묻는다. */
        var startupPermissionAsked = false
    }
}

/**
 * 씨나리오 메인 화면.
 * - 배경: 카메라 미리보기 전체
 * - 위쪽: 상태와 마지막으로 인식한 말
 * - 아래쪽: 묘사 결과
 * 화면 어디를 탭해도(TalkBack에서는 두 번 탭) 듣기를 시작한다. 탭은 이 화면에서만 처리한다.
 */
@Composable
fun SeenarioScreen(controller: SeenarioController, modifier: Modifier = Modifier) {
    val voiceState = controller.voice.uiState
    val missing = controller.missingPermissions
    val cameraGranted = Manifest.permission.CAMERA !in missing
    val tapLabel = when {
        !voiceState.speechAvailable -> "풍경 묘사 요청"
        !voiceState.hasMicPermission -> "권한 다시 요청"
        else -> "음성 명령 말하기"
    }
    val hint = when {
        missing.isNotEmpty() -> "권한이 필요해요. 화면을 누르면 다시 물어볼게요."
        voiceState.speechAvailable -> "화면을 누르거나 볼륨 아래 버튼을 누르고 '묘사해줘'라고 말해 주세요."
        else -> "화면을 누르면 주변을 묘사해 드려요."
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted) {
            CameraStreamScreen(
                describer = controller.describer,
                trigger = controller.trigger,
                onDescribed = controller::onDescribed,
                onError = controller::onDescribeFailed,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 탭 영역은 글자 아래에 깔아서, 글자 위를 눌러도 여기로 온다.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = controller.voice::onTriggerPressed)
                .semantics { contentDescription = "화면 전체 버튼" },
        )

        StatusPanel(
            status = controller.status,
            lastHeard = voiceState.lastHeard,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(16.dp),
        )

        val result = controller.resultText
        val announce = result != null && !controller.resultNarrated
        Text(
            text = result ?: hint,
            fontSize = 24.sp,
            lineHeight = 34.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp, vertical = 20.dp)
                // TTS가 읽은 글은 TalkBack이 자동으로 또 읽지 않게 한다.
                .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun StatusPanel(status: SeenarioStatus, lastHeard: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = status.label,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.semantics { contentDescription = "현재 상태: ${status.label}" },
        )
        if (lastHeard != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "“$lastHeard”",
                fontSize = 20.sp,
                lineHeight = 28.sp,
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "마지막으로 인식한 말: $lastHeard" },
            )
        }
    }
}
