package com.rj.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real SDK request/response path against a local mock of the Messages API. */
class ClaudeBrainTest {
    private val server = MockWebServer()
    private val mapper = ObjectMapper()

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.shutdown()

    private fun reply(text: String, stop: String = "end_turn") = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody(
            mapper.writeValueAsString(
                mapOf(
                    "id" to "msg_1", "type" to "message", "role" to "assistant", "model" to "claude-opus-5-5",
                    "content" to listOf(mapOf("type" to "text", "text" to text)),
                    "stop_reason" to stop, "stop_sequence" to null,
                    "usage" to mapOf("input_tokens" to 10, "output_tokens" to 10),
                )
            )
        )

    private fun brain() = ClaudeBrain(
        apiKey = "test",
        location = ClaudeBrain.Location("Pune", "Maharashtra", "IN", "Asia/Kolkata"),
        client = AnthropicOkHttpClient.builder().apiKey("test").baseUrl(server.url("/").toString())
            .maxRetries(0).build(),
    )

    private val ctx = ShowContext(now = ZonedDateTime.now(), profile = UserProfile(name = "Sam"), drive = null)

    @Test fun sendsPersonaContextToolsAndParsesSegment() {
        server.enqueue(reply("""{"speech":"Hello Sam","song":{"artist":"ABBA","title":"Dancing Queen"},"listen":false}"""))
        server.enqueue(reply("""{"speech":"That was ABBA","song":null}"""))

        val b = brain()
        b.startShow(Persona(carName = "Bolt"), UserProfile(name = "Sam"), emptyList())
        val seg = b.next(Cue(CueType.OPENER), ctx)
        assertEquals("Hello Sam", seg.speech)
        assertEquals("Dancing Queen", seg.song?.title)

        val first = mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals("claude-opus-5-5", first["model"].asText())
        assertTrue(first["system"][0]["text"].asText().contains("You are Bolt"))
        assertEquals("web_search", first["tools"][0]["name"].asText())
        assertEquals("Pune", first["tools"][0]["user_location"]["city"].asText())
        assertEquals("default", first["fallbacks"].asText())
        assertEquals("low", first["output_config"]["effort"].asText())
        assertTrue(first["messages"][0]["content"].asText().contains("[CUE: OPENER]"))

        b.next(Cue(CueType.LINK), ctx)
        val second = server.takeRequest()
        assertTrue(second.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = mapper.readTree(second.body.readUtf8())
        // History is append-only: user, assistant, user.
        assertEquals(listOf("user", "assistant", "user"), body["messages"].map { it["role"].asText() })
    }

    @Test fun resumesPausedTurn() {
        server.enqueue(reply("", stop = "pause_turn"))
        server.enqueue(reply("""{"speech":"Here's the news"}"""))
        val b = brain()
        b.startShow(Persona(), UserProfile(), emptyList())
        assertEquals("Here's the news", b.next(Cue(CueType.NEWS_BREAK), ctx).speech)
        assertEquals(2, server.requestCount)
    }
}
