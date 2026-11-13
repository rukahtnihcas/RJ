package earth.ripple.app

import android.graphics.Bitmap
import earth.ripple.core.Dimension
import earth.ripple.core.KnowledgeBase
import earth.ripple.core.Label
import earth.ripple.core.Tier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The OpenAI-compatible client against a fake service that behaves like the real free ones can. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiAnalystTest {
    private val server = MockWebServer()
    private val photo = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)

    private val answer = """{"identified":true,"name":"Wool sweater","emoji":"🧶","kind":"FASHION","unit":"one sweater","headline":"Wool is warm.",
        "scores":{"carbon":{"score":-3,"why":"sheep","metric":"≈ 20 kg CO₂e","confidence":"MEDIUM"},"water":{"score":-2,"why":"w","metric":"","confidence":"LOW"},
        "health":{"score":0.5,"why":"w","metric":"","confidence":"LOW"},"nature":{"score":-2.5,"why":"w","metric":"","confidence":"LOW"},
        "social":{"score":-1,"why":"w","metric":"","confidence":"LOW"}},
        "stages":[{"name":"Sheep","weight":3,"note":""}],"dependencies":[],"alternatives":[],"critical":[{"name":"Sheep farming","why":"methane"}],
        "look_for":[{"name":"Responsible Wool Standard","meaning":"audited","trust":"TRUSTED"}],"questions":["Is merino better?"],"sources":["x"]}"""

    private fun completion(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(content)}},"finish_reason":"stop"}]}"""
    )

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun analyst() = OpenAiAnalyst(server.url("/api/v1/").toString(), "test-key", "moonshotai/kimi-k2.6:free")

    @Test fun stepsDownWhenTheSchemaIsRejectedAndRemembers() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"response_format json_schema is not supported"}}"""))
        server.enqueue(completion("<think>looks like knitwear</think>\n```json\n$answer\n```"))
        server.enqueue(completion(answer))
        val a = analyst()
        val r = a.assess(photo, listOf(Label("a wool sweater", 0.8f)), null, null, "GB", "ai-1")
        assertEquals("Wool sweater", r.name); assertEquals(Tier.AI, r.tier)
        assertEquals(-3.0, r.score(Dimension.CARBON).score, 0.0)
        assertEquals("Sheep farming", r.critical.single().name)

        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("/api/v1/chat/completions", first.path)
        assertEquals("Bearer test-key", first.getHeader("Authorization"))
        val body1 = first.body.readUtf8(); val body2 = second.body.readUtf8()
        assertTrue(body1.contains("json_schema") && body1.contains("data:image/jpeg;base64,"))
        assertTrue(body2.contains("json_object") && !body2.contains("json_schema"))

        a.assess(photo, emptyList(), null, null, null, "ai-2") // starts from the format that worked
        assertTrue(server.takeRequest().body.readUtf8().contains("json_object"))
    }

    @Test fun explainsFailuresInPlainWords() = runBlocking {
        suspend fun failure(response: MockResponse): String {
            server.enqueue(response)
            return try { analyst().assess(photo, emptyList(), null, null, null, "x"); fail(); "" } catch (e: AiFailure) { e.message!! }
        }
        assertTrue(failure(MockResponse().setResponseCode(429).setBody("{}")).contains("busy or its daily limit"))
        assertTrue(failure(MockResponse().setResponseCode(401).setBody("{}")).contains("key was not accepted"))
        assertTrue(failure(MockResponse().setResponseCode(404).setBody("{}")).contains("kimi-k2.6:free"))
        // Unreadable at every format: three attempts, then a clear message.
        repeat(3) { server.enqueue(completion("I think this is a jumper.")) }
        val msg = try { analyst().assess(photo, emptyList(), null, null, null, "x"); "" } catch (e: AiFailure) { e.message!! }
        assertTrue(msg, msg.contains("could not be read"))
    }

    @Test fun answersQuestions() = runBlocking {
        server.enqueue(completion("Merino is finer wool from merino sheep."))
        val text = analyst().ask(KnowledgeBase.byId("tshirt")!!, listOf("q" to "a"), "Is merino better?")
        assertEquals("Merino is finer wool from merino sheep.", text)
        assertTrue(server.takeRequest().body.readUtf8().contains("Is merino better?"))
    }
}
