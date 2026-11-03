package com.buzzerbeater.seenario.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResponseParsingTest {

    @Test
    fun parse_fullResponse() {
        val response = AgentJson.parseResponse(
            """
            {
              "speech": "햇살이 따뜻해요.",
              "display": "햇살이 따뜻한 창가예요.",
              "actions": [
                { "type": "play_sound", "sound_id": "abc", "title": "숲", "url": "https://x/y",
                  "volume": 0.5, "loopable": true },
                { "type": "stop_sound" }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("햇살이 따뜻해요.", response.speech)
        assertEquals("햇살이 따뜻한 창가예요.", response.display)
        assertEquals(2, response.actions.size)
        val play = response.actions[0]
        assertEquals(AgentAction.PLAY_SOUND, play.type)
        assertEquals("abc", play.soundId)
        assertEquals("숲", play.title)
        assertEquals("https://x/y", play.url)
        assertEquals(0.5, play.volume!!, 0.0001)
        assertEquals(true, play.loopable)
        val stop = response.actions[1]
        assertEquals(AgentAction.STOP_SOUND, stop.type)
        assertNull(stop.soundId)
        assertNull(stop.url)
    }

    @Test
    fun parse_missingActions_isEmpty() {
        val response = AgentJson.parseResponse("""{ "speech": "안녕", "display": "안녕" }""")
        assertTrue(response.actions.isEmpty())
    }

    @Test
    fun parse_ignoresUnknownFields() {
        val response = AgentJson.parseResponse(
            """{ "speech": "a", "display": "b", "debug": { "steps": 3 }, "actions": [{ "type": "stop_sound", "extra": 1 }] }""",
        )
        assertEquals("a", response.speech)
        assertEquals(AgentAction.STOP_SOUND, response.actions.single().type)
    }

    @Test(expected = Exception::class)
    fun parse_missingSpeech_fails() {
        AgentJson.parseResponse("""{ "display": "b" }""")
    }

    @Test
    fun encode_request_usesSnakeCaseAndWireValues() {
        val request = AgentRequest(
            input = "묘사해줘",
            inputType = InputType.DESCRIBE,
            history = listOf(HistoryItem(HistoryItem.ROLE_USER, "안녕"), HistoryItem(HistoryItem.ROLE_AGENT, "반가워요")),
            soundMode = SoundMode.AMBIENT,
        )
        val obj = Json.parseToJsonElement(AgentJson.encodeRequest(request)).jsonObject
        assertEquals("묘사해줘", obj.string("input"))
        assertEquals("describe", obj.string("input_type"))
        assertEquals("ambient", obj.string("sound_mode"))
        // 사진이 없으면 image 필드를 아예 보내지 않는다.
        assertFalse(obj.containsKey("image"))
        val history = obj.getValue("history").jsonArray
        assertEquals(2, history.size)
        assertEquals("user", history[0].jsonObject.string("role"))
        assertEquals("반가워요", history[1].jsonObject.string("text"))
    }

    @Test
    fun encode_request_includesImageAndDefaults() {
        val request = AgentRequest(input = "뭐 있어?", inputType = InputType.SPEECH, image = "QUJD")
        val obj = Json.parseToJsonElement(AgentJson.encodeRequest(request)).jsonObject
        assertEquals("speech", obj.string("input_type"))
        assertEquals("QUJD", obj.string("image"))
        assertEquals("off", obj.string("sound_mode"))
        assertTrue(obj.getValue("history").jsonArray.isEmpty())
    }

    @Test
    fun soundMode_fromWire() {
        assertEquals(SoundMode.AMBIENT, SoundMode.fromWire("ambient"))
        assertEquals(SoundMode.MUSIC, SoundMode.fromWire("music"))
        assertEquals(SoundMode.OFF, SoundMode.fromWire("off"))
        assertEquals(SoundMode.OFF, SoundMode.fromWire(null))
        assertEquals(SoundMode.OFF, SoundMode.fromWire("loud"))
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
