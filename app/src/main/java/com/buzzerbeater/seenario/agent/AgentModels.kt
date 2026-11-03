package com.buzzerbeater.seenario.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 에이전트에 보내는 입력 종류. */
@Serializable
enum class InputType(val wire: String) {
    /** 사용자가 한 말(질문, 대화). */
    @SerialName("speech")
    SPEECH("speech"),

    /** "묘사해줘" 또는 화면 길게 누르기. */
    @SerialName("describe")
    DESCRIBE("describe"),
}

/** 소리 모드. 사용자가 말로 고르고 `user_settings.sound_mode`에 [wire] 값으로 저장한다. */
@Serializable
enum class SoundMode(val wire: String, val label: String) {
    @SerialName("off")
    OFF("off", "기본 모드"),

    @SerialName("ambient")
    AMBIENT("ambient", "환경음"),

    @SerialName("music")
    MUSIC("music", "배경음");

    companion object {
        /** 모르는 값이나 null이면 기본값 [OFF]. */
        fun fromWire(value: String?): SoundMode = entries.firstOrNull { it.wire == value } ?: OFF
    }
}

/** 대화 기록 한 줄. */
@Serializable
data class HistoryItem(
    /** "user" 또는 "agent". */
    val role: String,
    val text: String,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_AGENT = "agent"
    }
}

/** 앱 → Edge Function `seenario-agent` 요청. 형식은 CLAUDE.md "앱 ↔ 에이전트 약속". */
@Serializable
data class AgentRequest(
    val input: String,
    @SerialName("input_type") val inputType: InputType,
    /** base64 JPEG. 카메라에서 최신 프레임을 받을 수 있게 되기 전까지는 null(전송 생략). */
    val image: String? = null,
    val history: List<HistoryItem> = emptyList(),
    @SerialName("sound_mode") val soundMode: SoundMode = SoundMode.OFF,
)

/** 에이전트 응답. [speech]는 TTS로 읽고 [display]는 화면에 보여 준다. */
@Serializable
data class AgentResponse(
    val speech: String,
    val display: String,
    val actions: List<AgentAction> = emptyList(),
)

/** 앱이 할 일. 지금은 소리 재생기가 없어서 로그로만 남긴다. */
@Serializable
data class AgentAction(
    /** "play_sound" 또는 "stop_sound". */
    val type: String,
    @SerialName("sound_id") val soundId: String? = null,
    val title: String? = null,
    val url: String? = null,
    val volume: Double? = null,
    val loopable: Boolean? = null,
) {
    companion object {
        const val PLAY_SOUND = "play_sound"
        const val STOP_SOUND = "stop_sound"
    }
}

/** 요청/응답 JSON 변환. 모르는 필드는 무시하고, null 필드는 보내지 않는다. */
object AgentJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeRequest(request: AgentRequest): String = json.encodeToString(AgentRequest.serializer(), request)

    fun parseResponse(text: String): AgentResponse = json.decodeFromString(AgentResponse.serializer(), text)
}
