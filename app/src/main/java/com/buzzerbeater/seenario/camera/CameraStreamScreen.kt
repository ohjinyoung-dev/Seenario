package com.buzzerbeater.seenario.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Range
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

private const val TAG = "SEENARIO"

/**
 * 카메라 권한을 확인하고 후면 카메라 미리보기를 띄운다.
 * [trigger]로 요청이 오면 가장 최근 프레임을 [describer]로 보내 결과를 [onDescribed]로 알려준다.
 * 실패하면 [onError]를 부른다. 탭 처리와 결과 표시·낭독은 화면 조립 쪽(SeenarioScreen)에서 한다.
 * [latestFrames]를 넘기면 가장 최근 프레임을 계속 채워 둬서, 밖에서 필요할 때 꺼내 쓸 수 있다.
 * [powerSave](절전 모드)면 미리보기를 띄우지 않고(검은 화면), 프레임도 요청받았을 때만 만든다.
 */
@Composable
fun CameraStreamScreen(
    describer: SceneDescriber,
    modifier: Modifier = Modifier,
    trigger: DescribeTrigger = remember { DescribeTrigger() },
    onDescribed: (String) -> Unit = {},
    onError: () -> Unit = {},
    latestFrames: LatestFrameProvider? = null,
    powerSave: Boolean = false,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    // 앱을 쓰는 동안 화면이 꺼지지 않게 한다(화면이 꺼지면 카메라도 멈춘다).
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    fun isGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(isGranted()) }
    // 두 번 거부하면 시스템이 권한 창을 더 띄우지 않으므로 설정 화면으로 안내해야 한다.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    // 설정 화면에서 권한을 켜고 돌아온 경우를 반영한다.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hasPermission) {
        CameraStream(describer, trigger, onDescribed, onError, latestFrames, powerSave, modifier)
    } else {
        PermissionRequest(
            permanentlyDenied = permanentlyDenied,
            onRequest = {
                if (permanentlyDenied) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun CameraStream(
    describer: SceneDescriber,
    trigger: DescribeTrigger,
    onDescribed: (String) -> Unit,
    onError: () -> Unit,
    latestFrames: LatestFrameProvider?,
    powerSave: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzer = remember { FrameStreamAnalyzer() }
    val previewView = remember {
        PreviewView(context).apply {
            // 시각 정보는 TalkBack이 읽을 수 없으므로 상태를 말로 알려준다.
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    // 절전 모드가 바뀌면 카메라를 다시 묶는다(미리보기 유무, 분석 형식이 달라짐).
    DisposableEffect(lifecycleOwner, powerSave) {
        analyzer.onDemand = powerSave
        latestFrames?.requester = analyzer::requestFrame
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            // 사진은 요청할 때 1장만 쓰므로 초당 30프레임이 필요 없다. 기기가 지원하는 범위에서 15프레임 근처로 낮춘다.
            val fps = lowFrameRate(cameraProvider)
            val analysis = FrameStreamAnalyzer.buildUseCase(powerSave, fps).also {
                it.setAnalyzer(executor, analyzer)
            }
            // 절전 모드에서는 미리보기 없이 분석만 묶는다.
            val useCases = if (powerSave) {
                arrayOf(analysis)
            } else {
                val preview = Preview.Builder()
                    .also { builder -> fps?.let { FrameStreamAnalyzer.setTargetFps(builder, it) } }
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                arrayOf(preview, analysis)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            latestFrames?.requester = null
            // 카메라가 꺼졌으므로 밖에서 예전 사진을 꺼내 쓰지 않게 비운다.
            latestFrames?.update(null)
        }
    }

    // 최신 프레임 1장만 메모리에 들고 있는다(저장하지 않음).
    var latestFrame by remember { mutableStateOf<CameraFrame?>(null) }
    LaunchedEffect(analyzer) {
        analyzer.frames.collect {
            latestFrame = it
            latestFrames?.update(it)
        }
    }

    var state by remember { mutableStateOf<DescribeState>(DescribeState.Idle) }
    val scope = rememberCoroutineScope()

    /** 묘사에 쓸 프레임. 절전 모드에서는 미리 만들어 둔 프레임이 없으므로 새로 요청해 기다린다. */
    suspend fun frameForDescribe(): CameraFrame? {
        val current = latestFrame
        if (!powerSave) return current
        analyzer.requestFrame()
        return withTimeoutOrNull(LatestFrameProvider.FRESH_TIMEOUT_MS) {
            snapshotFlow { latestFrame }.first { it != null && it !== current }
        } ?: current
    }

    fun describeNow() {
        if (state is DescribeState.Loading) return
        state = DescribeState.Loading
        scope.launch {
            val frame = frameForDescribe()
            if (frame == null) {
                Log.w(TAG, "Describe skipped: no frame yet")
                state = DescribeState.Error("카메라가 아직 준비 중이에요. 잠시 후 다시 눌러 주세요.")
                onError()
                return@launch
            }
            state = try {
                DescribeState.Done(describer.describe(frame)).also { onDescribed(it.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Describe failed", e)
                onError()
                DescribeState.Error("풍경을 묘사하지 못했어요. 다시 한 번 눌러 주세요.")
            }
        }
    }

    LaunchedEffect(trigger) {
        trigger.requests.collect { describeNow() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 절전 모드에서는 미리보기를 그리지 않는다(OLED에서 검은 화면은 전기를 거의 쓰지 않음).
        if (!powerSave) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

/** 후면 카메라가 지원하는 범위에서 [CameraFrameRate.pick]으로 고른 프레임 속도. 알 수 없으면 null(기본값). */
private fun lowFrameRate(provider: ProcessCameraProvider): Range<Int>? = try {
    val ranges = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA).supportedFrameRateRanges
    CameraFrameRate.pick(ranges.map { it.lower to it.upper })
        ?.let { (lower, upper) -> Range(lower, upper) }
        .also { Log.d(TAG, "카메라 프레임 속도: $it (지원: ${ranges.joinToString()})") }
} catch (e: Exception) {
    Log.w(TAG, "카메라 프레임 속도 범위를 알 수 없어 기본값 사용", e)
    null
}

private sealed interface DescribeState {
    val message: String

    data object Idle : DescribeState {
        override val message = "화면을 누르면 지금 보이는 풍경을 묘사해 드려요."
    }

    data object Loading : DescribeState {
        override val message = "풍경을 살펴보고 있어요…"
    }

    data class Done(override val message: String) : DescribeState
    data class Error(override val message: String) : DescribeState
}

@Composable
private fun PermissionRequest(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier,
) {
    val message = if (permanentlyDenied) {
        "카메라 권한이 꺼져 있어요. 설정에서 권한, 카메라를 차례로 눌러 허용해 주세요."
    } else {
        "풍경을 묘사하려면 카메라 권한이 필요해요."
    }
    val buttonLabel = if (permanentlyDenied) "설정 열기" else "카메라 권한 허용"
    val buttonDescription = if (permanentlyDenied) "앱 설정 화면 열기" else "카메라 권한 허용하기"
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(
            onClick = onRequest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .semantics { contentDescription = buttonDescription },
        ) {
            Text(buttonLabel)
        }
    }
}
