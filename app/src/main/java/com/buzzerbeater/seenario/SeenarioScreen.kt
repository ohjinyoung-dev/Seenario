package com.buzzerbeater.seenario

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
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
import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.AgentClient
import com.buzzerbeater.seenario.agent.AgentRequest
import com.buzzerbeater.seenario.agent.AgentResponse
import com.buzzerbeater.seenario.agent.ConversationHistory
import com.buzzerbeater.seenario.agent.InputType
import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.camera.CameraStreamScreen
import com.buzzerbeater.seenario.camera.DescribeTrigger
import com.buzzerbeater.seenario.camera.LatestFrameProvider
import com.buzzerbeater.seenario.camera.SceneDescriber
import com.buzzerbeater.seenario.data.SupabaseProvider
import com.buzzerbeater.seenario.data.UserSettings
import com.buzzerbeater.seenario.power.PowerSavePolicy
import com.buzzerbeater.seenario.sound.DataStoreLastSoundModeStorage
import com.buzzerbeater.seenario.sound.LastSoundMode
import com.buzzerbeater.seenario.sound.MediaPlayerSoundEngine
import com.buzzerbeater.seenario.sound.SoundActionRunner
import com.buzzerbeater.seenario.sound.SoundCache
import com.buzzerbeater.seenario.sound.SoundNoticeStorage
import com.buzzerbeater.seenario.sound.SoundModePolicy
import com.buzzerbeater.seenario.sound.SoundModeStore
import com.buzzerbeater.seenario.sound.SoundPlayer
import com.buzzerbeater.seenario.sound.SoundRequester
import com.buzzerbeater.seenario.voice.TalkBackSpeechMonitor
import com.buzzerbeater.seenario.voice.VoiceCommandListener
import com.buzzerbeater.seenario.voice.VoiceController
import com.buzzerbeater.seenario.voice.VoiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SEENARIO"

/** 에이전트 응답의 actions 등 에이전트 관련 로그. */
private const val AGENT_TAG = "SEENARIO_AGENT"

/** 이만큼 누르고 있으면 듣지 않고 바로 묘사한다. */
private const val LONG_PRESS_DESCRIBE_MS = 1_000L

/** 길게 눌러 바로 묘사할 때 진동 길이. */
private const val LONG_PRESS_VIBRATION_MS = 150L

/** 진동 세기 최댓값(1~255). */
private const val MAX_AMPLITUDE = 255

/** 화면 위쪽에 보여 주는 상태. 음성 상태([VoiceStatus])에 "생각하는 중"을 더했다. */
enum class SeenarioStatus(val label: String) {
    IDLE("대기 중"),
    LISTENING("듣는 중"),
    THINKING("생각하는 중"),
    SPEAKING("말하는 중"),
}

/**
 * 화면 회전 등으로 Activity(와 컨트롤러)가 다시 만들어져도 유지할 값. 프로세스가 끝나면(앱을 다시 켜면) 사라진다.
 */
private object Session {
    /** 에이전트에 같이 보내는 최근 대화 기록. "처음부터"라고 하면 지운다. */
    val history = ConversationHistory()

    /** 지금 소리 모드. 앱을 켜면 항상 기본 모드(off)로 시작한다. */
    var soundMode by mutableStateOf(SoundMode.OFF)

    /**
     * 사용자가 말로 고른 절전 모드("절전 모드 켜/꺼"). null이면 휴대폰 절전 모드를 따른다.
     * 휴대폰 절전 모드가 바뀌면 지운다. 저장하지 않으므로 앱을 다시 켜면 null이다.
     */
    var powerSaveChoice by mutableStateOf<Boolean?>(null)

    /** 사용자가 이미 말로 모드를 바꿨으면 늦게 끝난 시작 처리(DB를 off로 저장)가 덮어쓰지 않게 한다. */
    var soundModeChangedByUser = false

    private var lastSoundMode: LastSoundMode? = null

    /** 휴대폰에 저장된 "지난 모드". 프로세스당 하나만 만든다. */
    fun lastSoundMode(context: Context): LastSoundMode =
        lastSoundMode ?: LastSoundMode(DataStoreLastSoundModeStorage(context)).also { lastSoundMode = it }
}

/**
 * 음성 모듈(voice/), 에이전트(agent/), 카메라 모듈(camera/)을 잇는다.
 *
 * 흐름: 화면 탭 → 듣기 → 휴대폰 명령(다시, 설정, 처음부터, 소리 모드)이면 바로 처리,
 * 아니면 에이전트에 대화(speech)로 보낸다. "묘사해줘"(또는 화면 1초 길게 누르기)는 describe로 보낸다.
 * → 생각하는 중 → 응답의 speech는 TTS로, display는 아래쪽에 표시 → 대기 중.
 * describe가 에러로 실패하면 남은 시간 안에서 기존 describe-scene 묘사([DescribeTrigger.request])로 대신한다(20초 초과면 바로 실패 안내).
 *
 * 카메라·마이크 권한도 여기서 한 번에 요청한다. Activity의 onCreate에서 만든다.
 */
class SeenarioController(
    private val activity: ComponentActivity,
) : VoiceCommandListener, DefaultLifecycleObserver {

    private val lastSoundMode = Session.lastSoundMode(activity.applicationContext)

    private val powerManager = activity.getSystemService(PowerManager::class.java)

    /**
     * 시작 안내 끝에 지난 모드가 환경음/배경음이면 다시 켜는 방법을 한 줄 붙인다.
     * 휴대폰이 절전 모드면 절전 모드로 시작한다는 줄도 붙인다.
     */
    val voice = VoiceController(
        activity,
        this,
        requestPermissions = ::requestPermissions,
        startupGreeting = { greeting ->
            PowerSavePolicy.startupGreeting(
                SoundModePolicy.startupGreeting(greeting, lastSoundMode.get()),
                systemPowerSave = powerManager?.isPowerSaveMode == true,
            )
        },
    )
    val trigger = DescribeTrigger()
    val describer = SceneDescriber(SupabaseProvider.client)

    /** 카메라 화면이 채우는 최신 프레임. 에이전트 요청에 사진으로 넣는다. */
    val frames = LatestFrameProvider()
    private val agent = AgentClient.create(SupabaseProvider.client)
    private val soundModeStore = SoundModeStore(SupabaseProvider.client)

    /** 에이전트 응답의 play_sound / stop_sound를 실행한다. */
    private val player = SoundPlayer(
        scope = activity.lifecycleScope,
        source = SoundCache(activity.applicationContext),
        engineFactory = ::MediaPlayerSoundEngine,
        onStarted = { onSoundStarted() },
    )
    private val soundNotice = SoundNoticeStorage(activity.applicationContext)

    /** 소리 모드를 켤 때 보내는 조용한 소리 요청(input_type "sound"). */
    private val soundRequester = SoundRequester(agent)
    private var soundRequestJob: Job? = null

    /** 응답의 소리 지시를 최신 요청 우선 규칙([com.buzzerbeater.seenario.sound.SoundRequestOrder])으로 실행한다. */
    private val soundActions = SoundActionRunner(player)

    /** 처음 소리를 틀 때 하는 안전 안내를 이미 했는지. 저장소에서 읽기 전에는 false. */
    private var soundNoticeDone = false

    /** TalkBack이 말하는 중인지(API 30 이상에서만 알 수 있다). 소리를 낮출 때 쓴다. */
    private var talkBackSpeaking by mutableStateOf(false)
    private val talkBackMonitor = TalkBackSpeechMonitor(
        activity.applicationContext,
        Handler(Looper.getMainLooper()),
        onStartedSpeaking = { talkBackSpeaking = true },
        onStoppedSpeaking = { talkBackSpeaking = false },
    )

    /** 에이전트(또는 대신하는 describe-scene) 응답을 기다리는 중. */
    var thinking by mutableStateOf(false)
        private set

    /** 휴대폰 절전 모드가 켜져 있는지. 화면에 있는 동안 [powerSaveReceiver]로 따라간다. */
    private var systemPowerSave by mutableStateOf(powerManager?.isPowerSaveMode == true)

    /** 지금 절전 모드인지. 미리보기를 끄고(검은 화면, 밝기 최소) 사진은 요청할 때만 만든다. */
    val powerSave: Boolean get() = PowerSavePolicy.isOn(Session.powerSaveChoice, systemPowerSave)

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onSystemPowerSaveChanged()
    }

    /** 지금 소리 모드. 화면 위쪽에 보여 준다. */
    val soundMode: SoundMode get() = Session.soundMode

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
            thinking -> SeenarioStatus.THINKING
            voice.uiState.status == VoiceStatus.SPEAKING -> SeenarioStatus.SPEAKING
            else -> SeenarioStatus.IDLE
        }

    /** "다시"라고 하면 읽어 줄 마지막 답변. */
    private var lastAnswer: String? = null

    /** describe-scene으로 대신 묘사하는 중이면 그 요청의 입력(대화 기록용). */
    private var fallbackInput: String? = null
    private var fallbackWatchdog: Job? = null
    private var requestingPermissions = false
    private var permanentlyDenied = false

    /**
     * 앱을 켜고 씨나리오가 시작 안내(권한 안내 → 시작 인사)를 읽는 동안 true.
     * 이때는 화면의 접근성 정보를 숨겨서 TalkBack이 화면 버튼을 같이 읽지 않게 한다.
     * 이전 화면에서 TalkBack이 읽던 말(예: 홈 화면 날씨 위젯)은 TtsSpeaker가 멈춤을 요청해서 끊는다.
     */
    var startupQuiet by mutableStateOf(!startupQuietDone)
        private set

    /** 시작할 때 권한이 거부되어 시작 인사 대신 거부 안내를 읽었는지. */
    private var startupDenied by mutableStateOf(false)

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        ::onPermissionResult,
    )

    init {
        // VoiceController를 먼저 등록해서 onResume 순서가 음성 → 화면 조립이 되게 한다.
        activity.lifecycle.addObserver(voice)
        activity.lifecycle.addObserver(this)
        if (startupQuiet) quietTalkBackDuringStartup()
        activity.lifecycleScope.launch { soundNoticeDone = soundNoticeDone || soundNotice.isDone() }
        duckWhileSpeaking()
        dimWhilePowerSave()
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

    override fun onStart(owner: LifecycleOwner) {
        ContextCompat.registerReceiver(
            activity,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 화면 밖에 있는 동안 휴대폰 절전 모드가 바뀌었을 수 있다.
        onSystemPowerSaveChanged()
    }

    /** 앱이 화면에서 사라지면 소리를 바로 멈춘다. 돌아와도 다시 틀지 않고 다음 play_sound를 기다린다. */
    override fun onStop(owner: LifecycleOwner) {
        activity.unregisterReceiver(powerSaveReceiver)
        cancelSoundRequest("앱이 화면에서 사라짐")
        player.stop(fade = false, reason = "앱이 화면에서 사라짐")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        player.release()
        talkBackMonitor.release()
    }

    /**
     * 씨나리오 TTS나 TalkBack이 말하는 동안 소리를 원래 볼륨의 30%로 낮추고, 끝나면 되돌린다.
     * 문장 사이의 짧은 쉼에 볼륨이 오르내리지 않도록 조용해진 뒤 [UNDUCK_DELAY_MS] 기다렸다가 되돌린다.
     */
    private fun duckWhileSpeaking() {
        activity.lifecycleScope.launch {
            snapshotFlow { voice.uiState.status == VoiceStatus.SPEAKING || talkBackSpeaking }
                .distinctUntilChanged()
                .collectLatest { speaking ->
                    if (!speaking) delay(UNDUCK_DELAY_MS)
                    player.setDucking(speaking)
                }
        }
    }

    /** "절전 모드 켜/꺼". 휴대폰 절전 모드보다 사용자 선택을 따른다(휴대폰 절전 모드가 다시 바뀔 때까지). */
    override fun onPowerSaveRequested(on: Boolean) {
        val wasOn = powerSave
        Session.powerSaveChoice = on
        Log.d(TAG, "절전 모드 ${if (on) "켜기" else "끄기"} (사용자)")
        voice.speak(PowerSavePolicy.userNotice(wasOn, on))
    }

    /** 휴대폰 절전 모드가 바뀌면 사용자 선택을 지우고 휴대폰을 따른다. 상태가 바뀌었을 때만 알린다. */
    private fun onSystemPowerSaveChanged() {
        val now = powerManager?.isPowerSaveMode == true
        if (now == systemPowerSave) return
        val wasOn = powerSave
        systemPowerSave = now
        Session.powerSaveChoice = null
        Log.d(TAG, "휴대폰 절전 모드 ${if (now) "켜짐" else "꺼짐"}")
        PowerSavePolicy.systemChangeNotice(wasOn, now)?.let { voice.speak(it) }
    }

    /** 절전 모드에서는 이 화면의 밝기를 최소로 낮추고, 끄면 시스템 밝기로 되돌린다. */
    private fun dimWhilePowerSave() {
        activity.lifecycleScope.launch {
            snapshotFlow { powerSave }.distinctUntilChanged().collect { on ->
                val window = activity.window
                window.attributes = window.attributes.apply {
                    screenBrightness = if (on) MIN_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                Log.d(TAG, if (on) "절전 모드 켜짐: 밝기 최소, 미리보기 끔" else "절전 모드 꺼짐: 밝기 되돌림, 미리보기 켬")
            }
        }
    }

    /**
     * 화면을 길게 눌렀을 때(TalkBack에서는 두 번 탭하고 누르고 있기). 진동으로 알리고 바로 묘사한다.
     * 터치 피드백 진동은 시스템 '터치 시 진동' 설정이 꺼져 있으면 무시되므로,
     * 진동 용도를 접근성으로 지정해서 울린다.
     */
    fun onLongPressDescribe() {
        vibrate()
        onDescribeRequested()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            activity.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        // 세기를 조절할 수 있는 기기면 최대 세기로, 아니면 기본 세기로 울린다.
        val amplitude = if (vibrator.hasAmplitudeControl()) MAX_AMPLITUDE else VibrationEffect.DEFAULT_AMPLITUDE
        val effect = VibrationEffect.createOneShot(LONG_PRESS_VIBRATION_MS, amplitude)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build(),
            )
        }
    }

    override fun onDescribeRequested(heard: String?) {
        askAgent(InputType.DESCRIBE, heard ?: DESCRIBE_INPUT)
    }

    override fun onSpeechInput(text: String) {
        askAgent(InputType.SPEECH, text)
    }

    override fun onRepeatRequested() {
        val last = lastAnswer
        when {
            last == null -> voice.speak(MSG_NOTHING_TO_REPEAT)
            thinking -> voice.speak(last)
            else -> show(last)
        }
    }

    override fun onResetRequested() {
        Session.history.clear()
        Log.d(AGENT_TAG, "대화 기록 초기화")
        voice.speak(MSG_RESET)
    }

    override fun onOpenSettingsRequested() {
        openAppSettings()
    }

    override fun onSoundModeRequested(mode: SoundMode) = changeSoundMode(mode)

    /** "소리 켜": 지난 모드가 환경음/배경음이면 그 모드로, 기본 모드이거나 없으면 환경음으로. */
    override fun onSoundOnRequested() {
        activity.lifecycleScope.launch { changeSoundMode(lastSoundMode.modeForSoundOn()) }
    }

    /**
     * 시작할 때 MainActivity가 로그인과 user_settings 읽기를 마치면 부른다.
     * 앱은 항상 기본 모드(off)로 시작하므로 DB의 sound_mode도 off로 저장한다.
     * 사용자가 고른 것이 아니므로 "지난 모드"에는 기록하지 않는다.
     */
    fun onSettingsLoaded(settings: UserSettings) {
        if (Session.soundModeChangedByUser) return
        Session.soundMode = SoundMode.OFF
        if (settings.soundMode != SoundMode.OFF.wire) saveSoundModeToDb(SoundMode.OFF)
    }

    /** 소리 모드를 바꾸고 TTS로 알린 뒤, 지난 모드(휴대폰)와 user_settings.sound_mode(DB)에 저장한다. */
    private fun changeSoundMode(mode: SoundMode) {
        val old = Session.soundMode
        Session.soundModeChangedByUser = true
        Session.soundMode = mode
        // 기본 모드로 바뀌거나 환경음 ↔ 배경음으로 바뀌면 지금 소리를 페이드로 멈춘다.
        // 사용자가 직접 말한 명령이므로 요청 순서 규칙과 상관없이 바로 적용한다.
        soundActions.onUserSoundModeChanged(old, mode)
        // 처음 소리를 켤 때는 모드 안내 뒤에 안전 안내를 이어 읽는다(소리 요청의 응답은 읽지 않으므로).
        val turningOn = mode != SoundMode.OFF
        voice.speak(if (turningOn && !soundNoticeDone) "${soundModeNotice(mode)} $MSG_SOUND_SAFETY" else soundModeNotice(mode))
        if (turningOn) requestSound(mode, sameMode = old == mode) else cancelSoundRequest("기본 모드로 바뀜")
        activity.lifecycleScope.launch { lastSoundMode.record(mode) }
        saveSoundModeToDb(mode)
    }

    private fun saveSoundModeToDb(mode: SoundMode) {
        activity.lifecycleScope.launch {
            try {
                soundModeStore.save(mode)
                Log.d(TAG, "sound_mode 저장: ${mode.wire}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sound_mode 저장 실패", e)
            }
        }
    }

    /**
     * 소리 모드를 켤 때 조용한 소리 요청을 보낸다. "생각하는 중"으로 바꾸지 않아서 사용자는 바로 다른 말을 할 수 있다.
     * 응답의 speech는 읽지 않고 actions만 실행한다. 같은 모드에서 이미 소리가 나오고 있으면 보내지 않는다.
     */
    private fun requestSound(mode: SoundMode, sameMode: Boolean) {
        if (sameMode && player.activeSoundId != null) {
            Log.d(AGENT_TAG, "[sound] 같은 모드에서 이미 재생 중이라 요청하지 않음")
            return
        }
        cancelSoundRequest("새 소리 요청")
        val seq = soundActions.nextRequest()
        soundRequestJob = activity.lifecycleScope.launch {
            // 절전 모드에서는 사진을 이때 새로 만든다(최대 1초).
            val image = frames.fresh()?.jpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val result = try {
                withTimeout(AGENT_TIMEOUT_MS) { soundRequester.request(image, mode) }
            } catch (e: TimeoutCancellationException) {
                Log.w(AGENT_TAG, "[sound] 응답 시간 초과")
                null
            } ?: return@launch
            runSoundActions(result.actions, mode, seq)
        }
    }

    /** 진행 중인 조용한 소리 요청이 있으면 취소한다. */
    private fun cancelSoundRequest(reason: String) {
        val job = soundRequestJob ?: return
        soundRequestJob = null
        if (job.isActive) {
            job.cancel()
            Log.d(AGENT_TAG, "[sound] 요청 취소 ($reason)")
        }
    }

    /** 사용자 입력을 에이전트로 보낸다. 처리 중이면 안내만 하고 무시한다. */
    private fun askAgent(type: InputType, input: String) {
        if (thinking) {
            voice.speak(MSG_BUSY)
            return
        }
        if (type == InputType.DESCRIBE) {
            refreshPermissions()
            if (Manifest.permission.CAMERA in missingPermissions) {
                requestPermissions()
                return
            }
            show(TEXT_LOOKING, speech = MSG_LOOKING)
        } else {
            show(TEXT_THINKING, speech = MSG_THINKING)
        }
        thinking = true
        val seq = soundActions.nextRequest()
        // 사진 받기, 에이전트, 대신 묘사를 합쳐 전체 대기 시간이 AGENT_TIMEOUT_MS를 넘지 않게 한다.
        val startMs = SystemClock.elapsedRealtime()
        activity.lifecycleScope.launch {
            // 최신 카메라 사진(긴 변 768px JPEG). 절전 모드에서는 이때 새로 만든다(최대 1초).
            // 카메라 준비 전이거나 권한이 없으면 null이라 사진 없이 보낸다.
            // describe에 사진이 없으면 서버가 400 image_required를 주고, 아래에서 describe-scene으로 대신 묘사한다.
            val jpeg = frames.fresh()?.jpeg
            val request = AgentRequest(
                input = input,
                inputType = type,
                image = jpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                history = Session.history.snapshot(),
                soundMode = Session.soundMode,
            )
            if (BuildConfig.DEBUG) {
                // 사진은 포함 여부와 크기만 남긴다.
                val image = if (jpeg != null) "yes(${(jpeg.size + 1023) / 1024}KB)" else "no"
                Log.d(
                    AGENT_TAG,
                    "request input_type=${type.wire}, sound_mode=${request.soundMode.wire}, " +
                        "history=${request.history.size}, image=$image, power_save=$powerSave",
                )
            }
            val response = try {
                val leftMs = AGENT_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startMs)
                withTimeout(leftMs) { agent.send(request) }
            } catch (e: TimeoutCancellationException) {
                // 시간을 다 썼으므로 대신 묘사 없이 바로 실패로 끝낸다.
                Log.w(AGENT_TAG, "에이전트 응답 시간 초과", e)
                failAgent()
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AGENT_TAG, "에이전트 요청 실패", e)
                null
            }
            val remainingMs = AGENT_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startMs)
            when {
                response != null -> onAgentResponse(input, response, request.soundMode, seq)
                type == InputType.DESCRIBE && remainingMs > 0 -> startFallbackDescribe(input, remainingMs)
                else -> failAgent()
            }
        }
    }

    private fun failAgent() {
        thinking = false
        show(MSG_ERROR)
    }

    private fun onAgentResponse(input: String, response: AgentResponse, requestMode: SoundMode, seq: Int) {
        thinking = false
        val willPlay = runSoundActions(response.actions, requestMode, seq)
        Session.history.addExchange(input, response.speech)
        lastAnswer = response.speech
        // 처음 소리를 틀 때는 응답 뒤에 안전 안내를 이어 읽는다(TTS만. 화면, 대화 기록, "다시"에는 넣지 않음).
        val speech = if (willPlay && !soundNoticeDone) "${response.speech} $MSG_SOUND_SAFETY" else response.speech
        show(response.display, speech = speech)
    }

    /**
     * 응답의 소리 지시를 실행한다. 새 소리를 틀기 시작하면 true.
     * 다음 경우에는 play_sound를 실행하지 않는다.
     * - 응답을 기다리는 동안 기본 모드나 다른 소리 모드로 바뀜, 앱이 화면에 없음 (여기서 판단)
     * - 더 나중에 보낸 요청의 소리 지시(play_sound/stop_sound)가 이미 실행됨 ([SoundActionRunner]가 판단)
     * stop_sound도 더 나중에 보낸 요청의 소리 지시가 이미 실행됐으면 버린다.
     */
    private fun runSoundActions(actions: List<AgentAction>, requestMode: SoundMode, seq: Int): Boolean {
        actions.forEach {
            Log.d(AGENT_TAG, "action #$seq type=${it.type}, sound_id=${it.soundId}, title=${it.title}")
        }
        return soundActions.run(actions, seq) {
            val mode = Session.soundMode
            when {
                mode == SoundMode.OFF || mode != requestMode ->
                    "지금 모드 ${mode.wire}, 요청할 때 모드 ${requestMode.wire}"
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> "앱이 화면에 없음"
                else -> null
            }
        }
    }

    /** 소리가 실제로 재생되기 시작하면 안전 안내를 한 것으로 기록한다(다운로드에 실패하면 다음에 다시 안내). */
    private fun onSoundStarted() {
        if (soundNoticeDone) return
        soundNoticeDone = true
        activity.lifecycleScope.launch { soundNotice.markDone() }
    }

    /**
     * describe 요청이 (시간 초과가 아닌) 에러로 실패하면 기존 describe-scene 묘사로 대신한다.
     * 남은 시간 [timeoutMs] 안에 결과가 [onDescribed]/[onDescribeFailed]로 오지 않으면 실패로 끝낸다.
     */
    private fun startFallbackDescribe(input: String, timeoutMs: Long) {
        Log.d(AGENT_TAG, "describe 에러 → describe-scene으로 대신 묘사 (남은 시간 ${timeoutMs}ms)")
        fallbackInput = input
        trigger.request()
        // 카메라 화면이 아직 요청을 받을 수 없는 상태였다면 결과가 오지 않으므로, 남은 시간이 지나면 실패로 끝낸다.
        fallbackWatchdog = activity.lifecycleScope.launch {
            delay(timeoutMs)
            Log.w(TAG, "Describe timed out")
            onDescribeFailed()
        }
    }

    fun onDescribed(description: String) {
        val input = fallbackInput ?: return
        finishFallback()
        Session.history.addExchange(input, description)
        lastAnswer = description
        show(description)
    }

    fun onDescribeFailed() {
        if (fallbackInput == null) return
        finishFallback()
        show(MSG_ERROR)
    }

    private fun finishFallback() {
        thinking = false
        fallbackInput = null
        fallbackWatchdog?.cancel()
        fallbackWatchdog = null
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
            startupDenied = true
        }
    }

    /**
     * 시작 안내가 끝날 때까지 TalkBack이 화면 버튼을 읽지 않게 한다.
     * 시작 인사(또는 권한 거부 안내)를 다 읽으면 원래대로 돌린다. TTS를 못 쓰는 기기는 바로 돌린다.
     */
    private fun quietTalkBackDuringStartup() {
        activity.lifecycleScope.launch {
            snapshotFlow { voice.uiState.greeted || startupDenied }.first { it }
            snapshotFlow { voice.uiState.status }.first { it != VoiceStatus.SPEAKING }
            startupQuietDone = true
            startupQuiet = false
            Log.d(TAG, "시작 안내 끝 → TalkBack에 화면 정보 공개")
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
        /** 에이전트 응답과 대신하는 describe-scene 묘사를 합쳐 기다리는 최대 시간. */
        const val AGENT_TIMEOUT_MS = 20_000L
        const val SPEECH_WAIT_MS = 15_000L

        /** 절전 모드의 화면 밝기(0~1). 0은 기기에 따라 화면을 완전히 끌 수 있어 조금 남긴다. */
        const val MIN_BRIGHTNESS = 0.01f

        /** 말이 끝나고 이만큼 조용하면 소리 볼륨을 되돌린다. */
        const val UNDUCK_DELAY_MS = 500L

        const val MSG_SOUND_SAFETY = "분위기를 위한 효과음이에요. 주변 소리가 잘 들리도록 안전한 곳에서 사용해 주세요."

        const val MSG_LOOKING = "잠시만요, 주변을 살펴볼게요."
        const val TEXT_LOOKING = "주변을 살펴보고 있어요…"
        const val MSG_THINKING = "잠시만요, 생각해 볼게요."
        const val TEXT_THINKING = "생각하고 있어요…"
        const val MSG_RESET = "대화를 처음부터 다시 시작할게요."

        /** 화면 길게 누르기처럼 들은 말이 없는 describe 요청의 input. */
        const val DESCRIBE_INPUT = "묘사해줘"
        const val MSG_BUSY = "지금 살펴보는 중이에요"
        const val MSG_ERROR = "주변을 살펴보지 못했어요. 다시 시도해 주세요."
        const val MSG_NOTHING_TO_REPEAT = "아직 들려드린 묘사가 없어요. '묘사해줘'라고 말해 주세요."
        const val MSG_DENIED_RETRY = "권한이 있어야 씨나리오를 쓸 수 있어요. 화면을 누르면 다시 물어볼게요."
        const val MSG_DENIED_SETTINGS =
            "권한이 꺼져 있어요. 화면을 누르면 설정 화면을 열게요. 권한에서 카메라와 마이크를 허용해 주세요."
        const val MSG_OPEN_SETTINGS = "설정 화면을 열게요. 권한에서 카메라와 마이크를 허용해 주세요."

        fun soundModeNotice(mode: SoundMode): String = when (mode) {
            SoundMode.OFF -> "기본 모드로 바꿨어요."
            SoundMode.AMBIENT -> "환경음 모드로 바꿨어요."
            SoundMode.MUSIC -> "배경음 모드로 바꿨어요."
        }

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

        /** 시작 안내 중 TalkBack 숨김은 프로세스당 한 번만. */
        var startupQuietDone = false
    }
}

/**
 * 씨나리오 메인 화면.
 * - 배경: 카메라 미리보기 전체(절전 모드에서는 검은 화면)
 * - 위쪽: 상태와 마지막으로 인식한 말
 * - 아래쪽: 묘사 결과
 * 화면 어디를 탭해도(TalkBack에서는 두 번 탭) 듣기를 시작한다. 탭은 이 화면에서만 처리한다.
 * 1초 이상 누르고 있으면(TalkBack에서는 두 번 탭하고 누르고 있기) 듣지 않고 바로 묘사한다.
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
        voiceState.speechAvailable -> "화면을 누르고 '묘사해줘'라고 말해 주세요. 1초 동안 길게 누르면 바로 묘사해 드려요."
        else -> "화면을 누르면 주변을 묘사해 드려요."
    }

    // 시작 안내 중에는 접근성 정보를 비워서 TalkBack이 씨나리오 TTS와 겹쳐 읽지 않게 한다.
    // 터치는 그대로 동작한다.
    val quiet = if (controller.startupQuiet) Modifier.clearAndSetSemantics {} else Modifier
    Box(modifier = modifier.fillMaxSize().background(Color.Black).then(quiet)) {
        if (cameraGranted) {
            CameraStreamScreen(
                describer = controller.describer,
                trigger = controller.trigger,
                onDescribed = controller::onDescribed,
                onError = controller::onDescribeFailed,
                latestFrames = controller.frames,
                powerSave = controller.powerSave,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 탭 영역은 글자 아래에 깔아서, 글자 위를 눌러도 여기로 온다.
        // 짧게 누르면 듣기, 1초 이상 누르고 있으면 손을 떼기 전에 바로 묘사(진동으로 알림).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var cancelled = false
                        val up = withTimeoutOrNull(LONG_PRESS_DESCRIBE_MS) {
                            waitForUpOrCancellation().also { if (it == null) cancelled = true }
                        }
                        when {
                            up != null -> {
                                up.consume()
                                controller.voice.onTriggerPressed()
                            }
                            // 1초 전에 취소(손가락이 화면 밖으로 나감 등)되면 아무것도 하지 않는다.
                            cancelled -> Unit
                            else -> {
                                controller.onLongPressDescribe()
                                waitForUpOrCancellation()?.consume()
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = "화면 전체 버튼"
                    role = Role.Button
                    onClick(label = tapLabel) {
                        controller.voice.onTriggerPressed()
                        true
                    }
                    onLongClick(label = "바로 풍경 묘사") {
                        controller.onLongPressDescribe()
                        true
                    }
                },
        )

        StatusPanel(
            status = controller.status,
            soundMode = controller.soundMode,
            powerSave = controller.powerSave,
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
private fun StatusPanel(
    status: SeenarioStatus,
    soundMode: SoundMode,
    powerSave: Boolean,
    lastHeard: String?,
    modifier: Modifier = Modifier,
) {
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
        Spacer(Modifier.height(4.dp))
        Text(
            text = "소리: ${soundMode.label}",
            fontSize = 20.sp,
            lineHeight = 28.sp,
            color = Color.White,
            modifier = Modifier.semantics { contentDescription = "현재 소리 모드: ${soundMode.label}" },
        )
        if (powerSave) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "절전 모드",
                fontSize = 20.sp,
                lineHeight = 28.sp,
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "절전 모드 켜짐. 카메라 화면을 끄고 배터리를 아끼는 중" },
            )
        }
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
