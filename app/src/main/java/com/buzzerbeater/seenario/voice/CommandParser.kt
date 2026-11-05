package com.buzzerbeater.seenario.voice

/**
 * 인식된 문장을 [VoiceCommand]로 분류한다.
 *
 * 공백을 모두 지운 뒤 키워드 포함 여부로 판단하므로 "소리 켜"와 "소리켜"를 같게 본다.
 * 규칙은 위에서부터 순서대로 검사한다(우선순위):
 * - "배경음 꺼"/"환경음 꺼"는 모드 바꾸기("배경음"/"환경음")보다 SOUND_OFF가 먼저 걸린다.
 * - "기본 모드", "소리 없이"도 SOUND_OFF(기본 모드)다.
 * - "조용히"와 "기본으로"는 질문에도 자주 나오므로, 문장 전체가 명령 꼴일 때만 SOUND_OFF다.
 *   예: "조용히", "조용히 해줘", "기본으로", "기본으로 돌아가" → SOUND_OFF.
 *   "여기 조용히 해야 하는 곳이야?" → 다른 규칙도 없으면 UNKNOWN(에이전트로 보냄).
 * - "묘사 저장해줘"는 SAVE, "다시 묘사해줘"는 DESCRIBE가 된다.
 * - "절전"/"절약"이 들어가면 가장 먼저 절전 모드 명령으로 본다. "꺼", "끄", "해제", "그만", "풀"이 같이 있으면 끄기.
 *   예: "절전 모드 켜", "배터리 절약 모드" → POWER_SAVE_ON, "절전 모드 꺼줘" → POWER_SAVE_OFF.
 * - 어느 규칙에도 맞지 않으면 UNKNOWN이고, 이 말은 에이전트에 대화로 보낸다.
 */
object CommandParser {

    private val rules: List<Pair<VoiceCommand, List<String>>> = listOf(
        VoiceCommand.SOUND_OFF to listOf(
            "소리꺼", "소리끄", "배경음꺼", "배경음끄", "환경음꺼", "환경음끄",
            "기본모드", "소리없이",
        ),
        VoiceCommand.SOUND_AMBIENT to listOf("환경음"),
        VoiceCommand.SOUND_MUSIC to listOf("배경음"),
        VoiceCommand.SOUND_ON to listOf("소리켜"),
        VoiceCommand.OPEN_SETTINGS to listOf("설정열어", "설정화면"),
        VoiceCommand.RESET to listOf("처음부터"),
        VoiceCommand.SAVE to listOf("저장"),
        VoiceCommand.DESCRIBE to listOf("묘사", "풍경", "주변", "뭐가보여"),
        VoiceCommand.REPEAT to listOf("다시", "한번더"),
    )

    fun parse(text: String): VoiceCommand {
        val normalized = text.filterNot { it.isWhitespace() }.lowercase()
        if (normalized.isEmpty()) return VoiceCommand.UNKNOWN
        if (POWER_SAVE_KEYWORDS.any { normalized.contains(it) }) {
            return if (POWER_SAVE_OFF_KEYWORDS.any { normalized.contains(it) }) {
                VoiceCommand.POWER_SAVE_OFF
            } else {
                VoiceCommand.POWER_SAVE_ON
            }
        }
        if (BASIC_MODE_COMMANDS.any { it.matches(normalized) }) return VoiceCommand.SOUND_OFF
        return rules.firstOrNull { (_, keywords) -> keywords.any { normalized.contains(it) } }
            ?.first
            ?: VoiceCommand.UNKNOWN
    }

    /**
     * 명령 꼴일 때만 기본 모드로 보는 "조용히"/"기본으로" 문장(공백을 지운 전체 문장과 비교).
     * 물음표가 붙은 문장이나 "조용히 해야 하는 곳이야"처럼 뒤에 다른 말이 이어지면 맞지 않는다.
     */
    private val BASIC_MODE_COMMANDS = listOf(
        Regex("""(소리)?(좀)?조용히(좀)?(해|해줘|해주세요|해줄래|하자)?[.!~]*"""),
        Regex("""(소리)?(좀)?기본으로(돌아가|돌아가줘|돌아가자|돌려|돌려줘|바꿔|바꿔줘|바꿔주세요|가|가줘|가자|해|해줘)?[.!~]*"""),
    )

    private val POWER_SAVE_KEYWORDS = listOf("절전", "절약")
    private val POWER_SAVE_OFF_KEYWORDS = listOf("꺼", "끄", "해제", "그만", "풀")

    /** 인식기의 후보 문장들(N-best) 중 UNKNOWN이 아닌 첫 결과를 고른다. */
    fun parse(candidates: List<String>): VoiceCommand =
        candidates.asSequence()
            .map { parse(it) }
            .firstOrNull { it != VoiceCommand.UNKNOWN }
            ?: VoiceCommand.UNKNOWN
}
