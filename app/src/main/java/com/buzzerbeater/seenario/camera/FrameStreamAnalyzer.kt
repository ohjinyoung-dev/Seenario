package com.buzzerbeater.seenario.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * CameraX ImageAnalysis 프레임을 일정 간격(기본 1fps)으로 골라
 * 회전 보정 → 긴 변 [maxEdgePx]로 축소 → JPEG 인코딩한 뒤 [frames]로 내보낸다.
 *
 * Gemini Live API 권장 입력이 1fps·저해상도라서 기본값을 그에 맞췄다.
 * 소비자가 느리면 가장 최신 프레임만 남기고 버린다.
 */
class FrameStreamAnalyzer(
    private val intervalMs: Long = 1_000L,
    private val maxEdgePx: Int = 768,
    private val jpegQuality: Int = 70,
) : ImageAnalysis.Analyzer {

    private val _frames = MutableSharedFlow<CameraFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<CameraFrame> = _frames.asSharedFlow()

    @Volatile
    var enabled: Boolean = true

    private var lastEmitMs = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (!enabled || _frames.subscriptionCount.value == 0) return
            if (now - lastEmitMs < intervalMs) return
            lastEmitMs = now

            val frame = encode(it, now)
            _frames.tryEmit(frame)
        }
    }

    private fun encode(image: ImageProxy, timestampMs: Long): CameraFrame {
        val source = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val scale = minOf(1f, maxEdgePx.toFloat() / max(source.width, source.height))

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val output = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        val bytes = ByteArrayOutputStream().use { out ->
            output.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
        val width = output.width
        val height = output.height
        if (output !== source) output.recycle()
        source.recycle()

        return CameraFrame(bytes, width, height, timestampMs)
    }

    companion object {
        /** 이 분석기에 맞게 설정된 ImageAnalysis use case를 만든다. */
        fun buildUseCase(): ImageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }
}
