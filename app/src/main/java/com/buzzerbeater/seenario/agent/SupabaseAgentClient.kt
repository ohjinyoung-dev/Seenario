package com.buzzerbeater.seenario.agent

import android.os.SystemClock
import android.util.Log
import com.buzzerbeater.seenario.data.UserSettingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Edge Function `seenario-agent`를 부른다. AI 호출과 API 키는 모두 서버에만 있다.
 * 서버가 에러(예: describe에 사진이 없을 때 400 image_required)를 돌려주면 예외가 나고,
 * 부르는 쪽이 대신할 동작(describe-scene 대신 묘사, 실패 안내)을 맡는다.
 */
class SupabaseAgentClient(
    private val supabase: SupabaseClient,
    private val userRepository: UserSettingsRepository = UserSettingsRepository(supabase),
) : AgentClient {

    override suspend fun send(request: AgentRequest): AgentResponse {
        // Edge Function이 사용자 토큰을 요구하므로 세션을 먼저 확보한다.
        userRepository.ensureSignedIn()
        val startMs = SystemClock.elapsedRealtime()
        val response = supabase.functions.invoke(FUNCTION_NAME) {
            contentType(ContentType.Application.Json)
            setBody(AgentJson.encodeRequest(request))
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
        val result = AgentJson.parseResponse(response.bodyAsText())
        Log.d(TAG, "agent ok in ${SystemClock.elapsedRealtime() - startMs}ms")
        return result
    }

    private companion object {
        const val TAG = "SEENARIO_AGENT"
        const val FUNCTION_NAME = "seenario-agent"

        /** 부르는 쪽의 20초 제한보다 조금 길게 둔다(먼저 끝나는 쪽이 부르는 쪽이 되게). */
        const val REQUEST_TIMEOUT_MS = 25_000L
    }
}
