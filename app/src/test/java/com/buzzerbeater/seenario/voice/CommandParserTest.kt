package com.buzzerbeater.seenario.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    private fun assertCommand(expected: VoiceCommand, text: String) =
        assertEquals("\"$text\"", expected, CommandParser.parse(text))

    @Test
    fun describe() {
        assertCommand(VoiceCommand.DESCRIBE, "지금 풍경 묘사해줘")
        assertCommand(VoiceCommand.DESCRIBE, "묘사해 줘")
        assertCommand(VoiceCommand.DESCRIBE, "주변에 뭐 있어")
        assertCommand(VoiceCommand.DESCRIBE, "뭐가 보여?")
        assertCommand(VoiceCommand.DESCRIBE, "뭐가보여")
    }

    @Test
    fun soundOnAndOff() {
        assertCommand(VoiceCommand.SOUND_ON, "소리 켜줘")
        assertCommand(VoiceCommand.SOUND_ON, "소리켜")
        assertCommand(VoiceCommand.SOUND_MUSIC, "배경음 틀어줘")
        assertCommand(VoiceCommand.SOUND_OFF, "소리 꺼줘")
        assertCommand(VoiceCommand.SOUND_OFF, "소리 끄기")
        assertCommand(VoiceCommand.SOUND_OFF, "배경음 꺼")
        assertCommand(VoiceCommand.SOUND_OFF, "환경음 꺼줘")
    }

    @Test
    fun basicMode_isSoundOff() {
        assertCommand(VoiceCommand.SOUND_OFF, "기본 모드")
        assertCommand(VoiceCommand.SOUND_OFF, "기본 모드로 가줘")
        assertCommand(VoiceCommand.SOUND_OFF, "기본 모드로 돌아가")
        assertCommand(VoiceCommand.SOUND_OFF, "기본으로")
        assertCommand(VoiceCommand.SOUND_OFF, "소리 없이")
        assertCommand(VoiceCommand.SOUND_OFF, "조용히")
        assertCommand(VoiceCommand.SOUND_OFF, "소리 꺼")
    }

    @Test
    fun quietAndBasic_asCommand_isSoundOff() {
        assertCommand(VoiceCommand.SOUND_OFF, "조용히 해줘")
        assertCommand(VoiceCommand.SOUND_OFF, "조용히 해")
        assertCommand(VoiceCommand.SOUND_OFF, "조용히 해 주세요.")
        assertCommand(VoiceCommand.SOUND_OFF, "좀 조용히 해줘")
        assertCommand(VoiceCommand.SOUND_OFF, "기본으로 돌아가")
        assertCommand(VoiceCommand.SOUND_OFF, "기본으로 바꿔줘")
        assertCommand(VoiceCommand.SOUND_OFF, "기본으로 가줘")
    }

    @Test
    fun quietAndBasic_inQuestion_goesToAgent() {
        assertCommand(VoiceCommand.UNKNOWN, "여기 조용히 해야 하는 곳이야?")
        assertCommand(VoiceCommand.UNKNOWN, "조용히 해야 해?")
        assertCommand(VoiceCommand.UNKNOWN, "조용히 해줘?")
        assertCommand(VoiceCommand.UNKNOWN, "사람들이 조용히 이야기하고 있어?")
        assertCommand(VoiceCommand.UNKNOWN, "기본으로 뭐가 있어?")
        assertCommand(VoiceCommand.UNKNOWN, "여기는 기본으로 시끄러운 곳이야?")
    }

    @Test
    fun turnOnByModeName() {
        assertCommand(VoiceCommand.SOUND_AMBIENT, "환경음 켜")
        assertCommand(VoiceCommand.SOUND_AMBIENT, "환경음 켜줘")
        assertCommand(VoiceCommand.SOUND_MUSIC, "배경음 켜")
        assertCommand(VoiceCommand.SOUND_MUSIC, "배경음 켜 줘")
        assertCommand(VoiceCommand.SOUND_ON, "소리 켜")
    }

    @Test
    fun soundModes() {
        assertCommand(VoiceCommand.SOUND_AMBIENT, "환경음 모드")
        assertCommand(VoiceCommand.SOUND_AMBIENT, "환경음 모드로 바꿔줘")
        assertCommand(VoiceCommand.SOUND_MUSIC, "배경음 모드")
        assertCommand(VoiceCommand.SOUND_MUSIC, "배경음모드 켜줘")
    }

    @Test
    fun phoneCommands() {
        assertCommand(VoiceCommand.OPEN_SETTINGS, "설정 열어줘")
        assertCommand(VoiceCommand.OPEN_SETTINGS, "설정 화면 보여줘")
        assertCommand(VoiceCommand.RESET, "처음부터")
        assertCommand(VoiceCommand.RESET, "처음부터 다시 하자")
        assertCommand(VoiceCommand.REPEAT, "다시")
    }

    @Test
    fun conversation_isUnknown() {
        // 휴대폰 명령이 아닌 말은 UNKNOWN → 에이전트에 대화로 보낸다.
        assertCommand(VoiceCommand.UNKNOWN, "여기 앉을 자리 있어?")
        assertCommand(VoiceCommand.UNKNOWN, "앞에 사람이 많아?")
    }

    @Test
    fun saveAndRepeat() {
        assertCommand(VoiceCommand.SAVE, "이거 저장해줘")
        assertCommand(VoiceCommand.REPEAT, "다시 말해줘")
        assertCommand(VoiceCommand.REPEAT, "한 번 더")
        assertCommand(VoiceCommand.REPEAT, "한번 더 들려줘")
    }

    @Test
    fun priority() {
        assertCommand(VoiceCommand.SAVE, "묘사 저장해줘")
        assertCommand(VoiceCommand.DESCRIBE, "다시 묘사해줘")
        assertCommand(VoiceCommand.SOUND_OFF, "배경음 꺼줘")
    }

    @Test
    fun unknown() {
        assertCommand(VoiceCommand.UNKNOWN, "")
        assertCommand(VoiceCommand.UNKNOWN, "   ")
        assertCommand(VoiceCommand.UNKNOWN, "오늘 날씨 어때")
    }

    @Test
    fun candidates_pickFirstKnown() {
        assertEquals(VoiceCommand.DESCRIBE, CommandParser.parse(listOf("뭐 사줘", "묘사해줘")))
        assertEquals(VoiceCommand.SAVE, CommandParser.parse(listOf("저장", "묘사")))
        assertEquals(VoiceCommand.UNKNOWN, CommandParser.parse(listOf("안녕", "고마워")))
        assertEquals(VoiceCommand.UNKNOWN, CommandParser.parse(emptyList()))
    }
    @Test
    fun powerSave() {
        assertCommand(VoiceCommand.POWER_SAVE_ON, "절전 모드 켜")
        assertCommand(VoiceCommand.POWER_SAVE_ON, "절전 모드로 바꿔줘")
        assertCommand(VoiceCommand.POWER_SAVE_ON, "배터리 절약 모드")
        assertCommand(VoiceCommand.POWER_SAVE_OFF, "절전 모드 꺼")
        assertCommand(VoiceCommand.POWER_SAVE_OFF, "절전 모드 꺼줘")
        assertCommand(VoiceCommand.POWER_SAVE_OFF, "절전 모드 끄기")
        assertCommand(VoiceCommand.POWER_SAVE_OFF, "절약 모드 해제")
        assertCommand(VoiceCommand.POWER_SAVE_OFF, "절전 모드 그만")
    }

    @Test
    fun powerSave_doesNotChangeOtherCommands() {
        assertCommand(VoiceCommand.SOUND_OFF, "소리 꺼")
        assertCommand(VoiceCommand.SOUND_OFF, "기본 모드")
        assertCommand(VoiceCommand.SOUND_AMBIENT, "환경음 모드")
        assertCommand(VoiceCommand.DESCRIBE, "묘사해줘")
    }
}
