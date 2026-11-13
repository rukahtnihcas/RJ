package earth.ripple.app

import android.graphics.Bitmap
import earth.ripple.core.AiProtocol
import earth.ripple.core.Assessment
import earth.ripple.core.Label
import earth.ripple.core.OpenAiCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Kimi, Gemini or any other service with an OpenAI-style chat completions endpoint.
 * Services differ in whether they honour a JSON schema, so requests step down from a strict
 * schema to plain JSON to no format at all, and remember what worked.
 */
class OpenAiAnalyst(baseUrl: String, private val apiKey: String, private val model: String) : Analyst {
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    private var format = OpenAiCompat.Format.SCHEMA

    override suspend fun assess(photo: Bitmap, labels: List<Label>, coarse: String?, productRecord: String?, country: String?, id: String): Assessment =
        withContext(Dispatchers.IO) {
            val image = jpegBase64(photo)
            val text = AiProtocol.userPrompt(labels, coarse, productRecord, country)
            var lastProblem = "The AI answer could not be read."
            for (f in OpenAiCompat.Format.entries.filter { it.ordinal >= format.ordinal }) {
                val (code, body) = post(OpenAiCompat.assessBody(model, text, image, f))
                if (code == 400 || code == 422) { lastProblem = describe(code, body); continue } // format not supported: try a looser one
                check(code, body)
                when (val r = OpenAiCompat.reply(body)) {
                    is OpenAiCompat.Reply.Text -> {
                        AiProtocol.parse(r.text, id)?.let { format = f; return@withContext it }
                        lastProblem = "The AI answer could not be read."
                    }
                    is OpenAiCompat.Reply.Refused -> throw AiFailure("The AI declined to assess this. Showing the on-device estimate.")
                    is OpenAiCompat.Reply.Error -> lastProblem = r.message
                }
            }
            throw AiFailure("$lastProblem Showing the on-device estimate.")
        }

    override suspend fun ask(about: Assessment, history: List<Pair<String, String>>, question: String): String = withContext(Dispatchers.IO) {
        val (code, body) = post(OpenAiCompat.chatBody(model, about, history, question))
        check(code, body)
        when (val r = OpenAiCompat.reply(body)) {
            is OpenAiCompat.Reply.Text -> r.text
            is OpenAiCompat.Reply.Refused -> throw AiFailure("The AI declined to answer that.")
            is OpenAiCompat.Reply.Error -> throw AiFailure(r.message)
        }
    }

    private fun post(json: String): Pair<Int, String> = try {
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Ripple") // OpenRouter shows this to the key owner
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: Exception) {
        throw AiFailure("Could not reach the AI service. Showing the on-device estimate.")
    }

    private fun check(code: Int, body: String) {
        if (code in 200..299) return
        throw AiFailure(describe(code, body) + " Showing the on-device estimate.")
    }

    private fun describe(code: Int, body: String): String {
        val detail = (OpenAiCompat.reply(body) as? OpenAiCompat.Reply.Error)?.message?.take(140)
        return when (code) {
            401, 403 -> "That API key was not accepted. Check it in Settings."
            402 -> "The AI service says this account has no credit left."
            404 -> "The model \"$model\" was not found on this service. Check the model name in Settings."
            429 -> "The free AI model is busy or its daily limit is used up. Try again later."
            in 500..599 -> "The AI service is having problems ($code)."
            else -> "The AI service returned an error ($code)${detail?.let { ": $it" } ?: ""}."
        }
    }
}
