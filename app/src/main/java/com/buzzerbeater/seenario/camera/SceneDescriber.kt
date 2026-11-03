package com.buzzerbeater.seenario.camera

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.buzzerbeater.seenario.data.UserSettingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 프레임 1장을 Supabase Edge Function `describe-scene`으로 보내 풍경 묘사 문장을 받아온다.
 * Gemini 호출과 API 키는 모두 Edge Function 쪽에만 있다.
 */
class SceneDescriber(
    private val supabase: SupabaseClient,
    private val userRepository: UserSettingsRepository = UserSettingsRepository(supabase),
) {
    suspend fun describe(frame: CameraFrame): String {
        // Edge Function이 사용자 토큰을 요구하므로 세션을 먼저 확보한다.
        userRepository.ensureSignedIn()

        val body = buildJsonObject {
            put("image", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP))
            put("mimeType", frame.mimeType)
        }
        val startMs = SystemClock.elapsedRealtime()
        val response = supabase.functions.invoke(FUNCTION_NAME) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            // Gemini 응답 + 서버 재시도 시간을 고려해 기본 10초보다 넉넉하게 준다.
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
        val result = json.decodeFromString<DescribeResponse>(response.bodyAsText())
        Log.d(TAG, "describe ok in ${SystemClock.elapsedRealtime() - startMs}ms (model=${result.model})")
        if (result.soundMode != null) {
            // 배경음/환경음 모드: 서버가 고른 음원(재생은 아직 미구현, 확인용 로그)
            Log.d(TAG, "sound mode=${result.soundMode} tags=${result.tags} sound=${result.sound?.title ?: "none"}")
        }
        return result.description
    }

    @Serializable
    private data class DescribeResponse(
        val description: String,
        val model: String? = null,
        val soundMode: String? = null,
        val tags: List<String> = emptyList(),
        val sound: SoundInfo? = null,
    )

    @Serializable
    private data class SoundInfo(val id: String, val title: String, val url: String, val loopable: Boolean = true)

    private companion object {
        const val TAG = "SEENARIO"
        const val FUNCTION_NAME = "describe-scene"
        const val REQUEST_TIMEOUT_MS = 40_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
