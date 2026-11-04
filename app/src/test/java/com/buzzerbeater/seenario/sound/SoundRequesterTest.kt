package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.AgentAction
import com.buzzerbeater.seenario.agent.AgentClient
import com.buzzerbeater.seenario.agent.AgentJson
import com.buzzerbeater.seenario.agent.AgentRequest
import com.buzzerbeater.seenario.agent.AgentResponse
import com.buzzerbeater.seenario.agent.AgentServerException
import com.buzzerbeater.seenario.agent.InputType
import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.agent.SoundRequests
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SoundRequesterTest {

    /** 보낸 요청을 기록하고, [answers]를 차례로 돌려주거나 던진다. */
    private class FakeAgent(vararg answers: Any) : AgentClient {
        private val queue = ArrayDeque(answers.toList())
        val sent = mutableListOf<AgentRequest>()

        override suspend fun send(request: AgentRequest): AgentResponse {
            sent += request
            return when (val next = queue.removeFirst()) {
                is AgentResponse -> next
                is Exception -> throw next
                else -> error("unexpected")
            }
        }
    }

    private val play = AgentAction(type = AgentAction.PLAY_SOUND, soundId = "rain", url = "https://example.invalid/x")
    private val ok = AgentResponse(speech = "", display = "", actions = listOf(play))
    private val logs = mutableListOf<String>()

    @Test
    fun soundRequest_format() {
        val request = SoundRequests.sound(image = "QUJD", mode = SoundMode.MUSIC)
        val json = AgentJson.json.parseToJsonElement(AgentJson.encodeRequest(request)) as JsonObject
        assertEquals("sound", json["input_type"]!!.jsonPrimitive.content)
        assertEquals("", json["input"]!!.jsonPrimitive.content)
        assertEquals("QUJD", json["image"]!!.jsonPrimitive.content)
        assertEquals("music", json["sound_mode"]!!.jsonPrimitive.content)
        assertEquals(0, (json["history"] as JsonArray).size)
    }

    @Test
    fun soundRequest_withoutImage_omitsImage() {
        val json = AgentJson.json.parseToJsonElement(
            AgentJson.encodeRequest(SoundRequests.sound(image = null, mode = SoundMode.AMBIENT)),
        ) as JsonObject
        assertFalse("image" in json)
        assertEquals("ambient", json["sound_mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun success_usesSoundOnly() = runBlocking {
        val agent = FakeAgent(ok)
        val result = SoundRequester(agent) { logs += it }.request("QUJD", SoundMode.AMBIENT)!!
        assertEquals(1, agent.sent.size)
        assertEquals(InputType.SOUND, agent.sent[0].inputType)
        assertFalse(result.viaFallback)
        assertEquals(listOf(play), result.actions)
    }

    @Test
    fun badRequest_fallsBackToSpeechOnce() = runBlocking {
        val agent = FakeAgent(AgentServerException(400, "invalid_input_type"), ok)
        val result = SoundRequester(agent) { logs += it }.request("QUJD", SoundMode.MUSIC)!!
        assertEquals(2, agent.sent.size)
        val fallback = agent.sent[1]
        assertEquals(InputType.SPEECH, fallback.inputType)
        assertEquals(SoundRequests.FALLBACK_INPUT, fallback.input)
        assertEquals("QUJD", fallback.image)
        assertEquals(SoundMode.MUSIC, fallback.soundMode)
        assertTrue(fallback.history.isEmpty())
        assertTrue(result.viaFallback)
        assertEquals(listOf(play), result.actions)
        assertTrue(logs.any { it.contains("임시 speech로 대신") })
    }

    @Test
    fun inputRequired_fromOldServer_alsoFallsBack() = runBlocking {
        // 지금 서버는 input이 ""이면 input_type을 보기 전에 400 input_required를 준다.
        val agent = FakeAgent(AgentServerException(400, "input_required"), ok)
        val result = SoundRequester(agent) { logs += it }.request("QUJD", SoundMode.AMBIENT)!!
        assertEquals(2, agent.sent.size)
        assertTrue(result.viaFallback)
    }

    @Test
    fun imageRequired_doesNotFallBack() = runBlocking {
        val agent = FakeAgent(AgentServerException(400, SoundRequests.IMAGE_REQUIRED))
        val result = SoundRequester(agent) { logs += it }.request(null, SoundMode.AMBIENT)
        assertNull(result)
        assertEquals(1, agent.sent.size)
    }

    @Test
    fun networkOrServerError_doesNotFallBack() = runBlocking {
        val network = FakeAgent(IOException("offline"))
        assertNull(SoundRequester(network) { logs += it }.request("QUJD", SoundMode.AMBIENT))
        assertEquals(1, network.sent.size)

        val server = FakeAgent(AgentServerException(500, "internal_error"))
        assertNull(SoundRequester(server) { logs += it }.request("QUJD", SoundMode.AMBIENT))
        assertEquals(1, server.sent.size)
    }

    @Test
    fun fallbackFails_returnsNull() = runBlocking {
        val agent = FakeAgent(AgentServerException(400, "invalid_input_type"), IOException("offline"))
        assertNull(SoundRequester(agent) { logs += it }.request("QUJD", SoundMode.AMBIENT))
        assertEquals(2, agent.sent.size)
    }

    @Test
    fun parseErrorCode() {
        assertEquals("image_required", AgentJson.parseErrorCode("""{"error":"image_required"}"""))
        assertNull(AgentJson.parseErrorCode("not json"))
        assertNull(AgentJson.parseErrorCode("""{"message":"x"}"""))
    }
}
