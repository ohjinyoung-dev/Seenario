package com.buzzerbeater.seenario.sound

import android.util.Log
import com.buzzerbeater.seenario.BuildConfig
import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.AgentClient
import com.buzzerbeater.seenario.agent.AgentServerException
import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.agent.SoundRequests
import kotlinx.coroutines.CancellationException

/**
 * 소리 모드를 켤 때 보내는 조용한 소리 요청. input_type "sound"로 보내고,
 * 서버가 sound를 모르면(400 등, image_required 제외) 임시 speech 요청으로 한 번 대신한다.
 * 응답의 speech는 쓰지 않고 actions만 돌려준다. 시간 제한은 부르는 쪽에서 건다.
 */
class SoundRequester(
    private val agent: AgentClient,
    private val log: (String) -> Unit = ::debugLog,
) {
    /** [viaFallback]: 임시 speech 요청으로 대신해서 받은 결과인지. */
    data class Result(val actions: List<AgentAction>, val viaFallback: Boolean)

    /** 실패하면 null(로그만 남긴다). */
    suspend fun request(image: String?, mode: SoundMode): Result? {
        val request = SoundRequests.sound(image, mode)
        log("[sound] 요청 sound_mode=${mode.wire}, image=${if (image != null) "yes" else "no"}")
        val error = try {
            val actions = agent.send(request).actions
            log("[sound] 응답 actions=${actions.map { it.type }}")
            return Result(actions, viaFallback = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        if (!SoundRequests.shouldFallback(error)) {
            log("[sound] 실패, 대신하지 않음: ${describe(error)}")
            return null
        }
        log("[sound] 서버가 sound를 모름(${describe(error)}) → 임시 speech로 대신 요청")
        return try {
            val actions = agent.send(SoundRequests.fallback(request)).actions
            log("[sound→speech 대신] 응답 actions=${actions.map { it.type }} (speech는 읽지 않음)")
            Result(actions, viaFallback = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[sound→speech 대신] 실패: ${describe(e)}")
            null
        }
    }

    private fun describe(e: Throwable): String =
        if (e is AgentServerException) "HTTP ${e.status} ${e.code}" else e.javaClass.simpleName

    private companion object {
        fun debugLog(message: String) {
            if (BuildConfig.DEBUG) Log.d("SEENARIO_AGENT", message)
        }
    }
}
