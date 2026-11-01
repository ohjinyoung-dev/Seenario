package com.buzzerbeater.seenario.voice

/**
 * 인식된 문장을 [VoiceCommand]로 분류한다.
 *
 * 공백을 모두 지운 뒤 키워드 포함 여부로 판단하므로 "소리 켜"와 "소리켜"를 같게 본다.
 * 규칙은 위에서부터 순서대로 검사한다(우선순위):
 * - "배경음 꺼"는 SOUND_ON("배경음")보다 SOUND_OFF가 먼저 걸린다.
 * - "묘사 저장해줘"는 SAVE, "다시 묘사해줘"는 DESCRIBE가 된다.
 */
object CommandParser {

    private val rules: List<Pair<VoiceCommand, List<String>>> = listOf(
        VoiceCommand.SOUND_OFF to listOf("소리꺼", "소리끄", "배경음꺼", "배경음끄"),
        VoiceCommand.SOUND_ON to listOf("소리켜", "배경음"),
        VoiceCommand.SAVE to listOf("저장"),
        VoiceCommand.DESCRIBE to listOf("묘사", "풍경", "주변", "뭐가보여"),
        VoiceCommand.REPEAT to listOf("다시", "한번더"),
    )

    fun parse(text: String): VoiceCommand {
        val normalized = text.filterNot { it.isWhitespace() }.lowercase()
        if (normalized.isEmpty()) return VoiceCommand.UNKNOWN
        return rules.firstOrNull { (_, keywords) -> keywords.any { normalized.contains(it) } }
            ?.first
            ?: VoiceCommand.UNKNOWN
    }

    /** 인식기의 후보 문장들(N-best) 중 UNKNOWN이 아닌 첫 결과를 고른다. */
    fun parse(candidates: List<String>): VoiceCommand =
        candidates.asSequence()
            .map { parse(it) }
            .firstOrNull { it != VoiceCommand.UNKNOWN }
            ?: VoiceCommand.UNKNOWN
}
