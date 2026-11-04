package com.buzzerbeater.seenario.agent

import android.util.Log
import com.buzzerbeater.seenario.BuildConfig
import com.buzzerbeater.seenario.data.UserSettingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 서버(`seenario-agent`)가 생기기 전까지 쓰는 가짜 에이전트. [delayMs] 뒤에 예시 응답을 돌려준다.
 * 소리 모드가 켜져 있으면 play_sound action도 넣는다.
 * - 디버그 빌드: `sounds` 테이블에서 같은 mode의 음원 하나를 골라 Storage signed URL(1시간)을 넣는다(배포 전 재생 테스트용).
 *   고르지 못하면 아래 예시 action으로 대신한다.
 * - 릴리스 빌드: url 없는 예시 action(재생되지 않는다).
 */
class FakeAgentClient(
    private val supabase: SupabaseClient? = null,
    private val delayMs: Long = 2_000L,
) : AgentClient {

    @Serializable
    private data class SoundRow(
        val id: String,
        val title: String,
        @SerialName("file_path") val filePath: String,
        val loopable: Boolean = true,
    )

    override suspend fun send(request: AgentRequest): AgentResponse {
        delay(delayMs)
        val text = when (request.inputType) {
            InputType.DESCRIBE -> DESCRIBE_SAMPLE
            InputType.SPEECH -> "'${request.input}'라고 물어보셨네요. " + SPEECH_SAMPLE
        }
        return AgentResponse(
            speech = text,
            display = text,
            actions = soundAction(request.soundMode)?.let(::listOf).orEmpty(),
        )
    }

    private suspend fun soundAction(mode: SoundMode): AgentAction? {
        if (mode == SoundMode.OFF) return null
        if (BuildConfig.DEBUG && supabase != null) realSound(supabase, mode)?.let { return it }
        return sampleAction(mode)
    }

    /** `sounds`에서 같은 mode의 사용 중인 음원 하나를 무작위로 골라 signed URL과 함께 돌려준다. 실패하면 null. */
    private suspend fun realSound(supabase: SupabaseClient, mode: SoundMode): AgentAction? = try {
        UserSettingsRepository(supabase).ensureSignedIn()
        val rows = supabase.from(SOUNDS_TABLE)
            .select(Columns.list("id", "title", "file_path", "loopable")) {
                filter {
                    eq("mode", mode.wire)
                    eq("is_active", true)
                }
            }
            .decodeList<SoundRow>()
        val row = rows.randomOrNull()
        if (row == null) {
            Log.d(TAG, "가짜 에이전트: ${mode.wire} 음원이 없어 예시 action 사용")
            null
        } else {
            val url = StorageSigner(supabase).sign(SOUNDS_BUCKET, row.filePath, SIGNED_URL_SEC)
            Log.d(TAG, "가짜 에이전트: 음원 고름 id=${row.id}, title=${row.title}")
            AgentAction(
                type = AgentAction.PLAY_SOUND,
                soundId = row.id,
                title = row.title,
                url = url,
                volume = 0.4,
                loopable = row.loopable,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 예외 메시지에 URL이 들어갈 수 있어서 종류만 남긴다.
        Log.w(TAG, "가짜 에이전트: 음원 고르기 실패 ${e.javaClass.simpleName} → 예시 action 사용")
        null
    }

    private fun sampleAction(mode: SoundMode): AgentAction? = when (mode) {
        SoundMode.OFF -> null
        SoundMode.AMBIENT -> AgentAction(
            type = AgentAction.PLAY_SOUND,
            soundId = "fake-ambient-forest",
            title = "숲속 바람 소리",
            volume = 0.4,
            loopable = true,
        )
        SoundMode.MUSIC -> AgentAction(
            type = AgentAction.PLAY_SOUND,
            soundId = "fake-music-calm-piano",
            title = "잔잔한 피아노",
            volume = 0.3,
            loopable = true,
        )
    }

    private companion object {
        const val TAG = "SEENARIO_AGENT"
        const val SOUNDS_TABLE = "sounds"
        const val SOUNDS_BUCKET = "sounds"
        const val SIGNED_URL_SEC = 3_600

        const val DESCRIBE_SAMPLE =
            "창가로 오후 햇살이 비스듬히 들어와 나무 책상 위에 따뜻한 빛을 펼쳐 놓았어요. " +
                "조용한 방 안에 바깥의 부드러운 바람이 살짝 스며드는 듯한, 느긋한 오후예요."
        const val SPEECH_SAMPLE =
            "지금은 연습용 답변이에요. 에이전트가 완성되면 주변 모습을 보고 대답해 드릴게요."
    }
}
