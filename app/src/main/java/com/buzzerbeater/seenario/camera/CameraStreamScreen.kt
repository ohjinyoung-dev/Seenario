package com.buzzerbeater.seenario.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "SEENARIO"

/**
 * 카메라 권한을 확인하고 후면 카메라 미리보기를 띄운다.
 * 화면을 탭하거나(TalkBack에서는 두 번 탭) [trigger]로 요청이 오면 가장 최근 프레임을 [describer]로 보내
 * 풍경 묘사를 하단에 보여주고 [onDescribed]로 알려준다(TTS 연결용).
 */
@Composable
fun CameraStreamScreen(
    describer: SceneDescriber,
    modifier: Modifier = Modifier,
    trigger: DescribeTrigger = remember { DescribeTrigger() },
    onDescribed: (String) -> Unit = {},
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
        CameraStream(describer, trigger, onDescribed, modifier)
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

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = FrameStreamAnalyzer.buildUseCase().also {
                it.setAnalyzer(executor, analyzer)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    // 최신 프레임 1장만 메모리에 들고 있는다(저장하지 않음).
    var latestFrame by remember { mutableStateOf<CameraFrame?>(null) }
    LaunchedEffect(analyzer) {
        analyzer.frames.collect { latestFrame = it }
    }

    var state by remember { mutableStateOf<DescribeState>(DescribeState.Idle) }
    val scope = rememberCoroutineScope()

    fun describeNow() {
        if (state is DescribeState.Loading) return
        val frame = latestFrame
        if (frame == null) {
            state = DescribeState.Error("카메라가 아직 준비 중이에요. 잠시 후 다시 눌러 주세요.")
            return
        }
        state = DescribeState.Loading
        scope.launch {
            state = try {
                DescribeState.Done(describer.describe(frame)).also { onDescribed(it.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Describe failed", e)
                DescribeState.Error("풍경을 묘사하지 못했어요. 다시 한 번 눌러 주세요.")
            }
        }
    }

    LaunchedEffect(trigger) {
        trigger.requests.collect { describeNow() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClickLabel = "지금 풍경 묘사 듣기") { describeNow() },
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Text(
            text = state.message,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
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
