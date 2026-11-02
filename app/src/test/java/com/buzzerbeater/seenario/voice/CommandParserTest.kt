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
        assertCommand(VoiceCommand.SOUND_ON, "배경음 틀어줘")
        assertCommand(VoiceCommand.SOUND_OFF, "소리 꺼줘")
        assertCommand(VoiceCommand.SOUND_OFF, "소리 끄기")
        assertCommand(VoiceCommand.SOUND_OFF, "배경음 꺼")
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
}
