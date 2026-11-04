package com.buzzerbeater.seenario.agent

/**
 * 조용한 소리 요청(input_type "sound")을 만드는 곳. 소리 요청은 여기서만 만든다.
 * 약속: 요청 `{ input_type: "sound", image, sound_mode(ambient/music), history(선택), input: "" }`,
 * 응답 `{ speech: "", display: "", actions: [play_sound] 또는 [] }`.
 */
object SoundRequests {

    /** 서버가 sound를 모를 때 임시로 보내는 speech 요청의 문장. */
    const val FALLBACK_INPUT = "지금 분위기에 어울리는 소리를 틀어 줘"

    const val IMAGE_REQUIRED = "image_required"

    /** 이 상태 코드면 서버가 sound를 모르는 것으로 보고 임시 speech로 대신한다(image_required 제외). */
    private val FALLBACK_STATUSES = setOf(400, 422)

    /** 소리만 고르는 요청. 대화 기록(history)은 보내지 않는다. */
    fun sound(image: String?, mode: SoundMode): AgentRequest = AgentRequest(
        input = "",
        inputType = InputType.SOUND,
        image = image,
        history = emptyList(),
        soundMode = mode,
    )

    /** 서버가 sound를 모를 때 대신 보내는 임시 speech 요청. 사진과 소리 모드는 그대로, 대화 기록은 보내지 않는다. */
    fun fallback(soundRequest: AgentRequest): AgentRequest = soundRequest.copy(
        input = FALLBACK_INPUT,
        inputType = InputType.SPEECH,
        history = emptyList(),
    )

    /** 소리 요청 실패 [error]가 "서버가 sound를 모름"이면 true. 사진이 없어서(400 image_required) 실패한 경우는 false. */
    fun shouldFallback(error: Throwable): Boolean =
        error is AgentServerException && error.status in FALLBACK_STATUSES && error.code != IMAGE_REQUIRED
}
