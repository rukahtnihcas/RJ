package earth.ripple.app

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import earth.ripple.core.AiProtocol
import earth.ripple.core.Assessment
import earth.ripple.core.Label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.time.Duration

/** Why an AI call produced nothing, phrased for the user. */
class AiFailure(message: String) : Exception(message)

/**
 * The AI tier: sends the photo to Claude and gets back a full assessment in a fixed JSON shape.
 * Refusals are routed to a fallback model by the API (server-side fallbacks), so a declined
 * request does not simply dead-end.
 */
class Ai(private val apiKey: String, private val model: String) {
    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(90)).maxRetries(1).build()
    }

    suspend fun assess(photo: Bitmap, labels: List<Label>, coarse: String?, productRecord: String?, country: String?, id: String): Assessment =
        withContext(Dispatchers.IO) {
            val params = base(AiProtocol.systemPrompt)
                .maxTokens(16000L)
                .outputConfig(
                    BetaOutputConfig.builder()
                        // Scoring leans on knowledge more than long reasoning, and the user is standing there waiting.
                        .effort(BetaOutputConfig.Effort.LOW)
                        .format(BetaJsonOutputFormat.builder().schema(schema).build())
                        .build()
                )
                .addUserMessageOfContentBlockParams(
                    listOf(
                        BetaContentBlockParam.ofImage(
                            BetaImageBlockParam.builder().source(
                                BetaBase64ImageSource.builder().data(jpegBase64(photo)).mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).build()
                            ).build()
                        ),
                        BetaContentBlockParam.ofText(
                            BetaTextBlockParam.builder().text(AiProtocol.userPrompt(labels, coarse, productRecord, country)).build()
                        ),
                    )
                )
                .build()
            val text = textOf(call(params))
            AiProtocol.parse(text, id) ?: throw AiFailure("The AI answer could not be read. Showing the on-device estimate.")
        }

    suspend fun ask(about: Assessment, history: List<Pair<String, String>>, question: String): String = withContext(Dispatchers.IO) {
        val b = base(AiProtocol.chatSystemPrompt + "\n\nASSESSMENT\n" + AiProtocol.chatContext(about))
            .maxTokens(2000L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        for ((q, a) in history) { b.addUserMessage(q); b.addAssistantMessage(a) }
        b.addUserMessage(question)
        textOf(call(b.build())).trim()
    }

    private fun base(system: String): MessageCreateParams.Builder =
        MessageCreateParams.builder()
            .model(model)
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .systemOfBetaTextBlockParams(
                listOf(BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build())
            )

    private fun call(params: MessageCreateParams): BetaMessage = try {
        client.beta().messages().create(params)
    } catch (e: UnauthorizedException) {
        throw AiFailure("That API key was not accepted. Check it in Settings.")
    } catch (e: RateLimitException) {
        throw AiFailure("The AI service is busy. Showing the on-device estimate.")
    } catch (e: AnthropicServiceException) {
        throw AiFailure("The AI service returned an error (${e.statusCode()}). Showing the on-device estimate.")
    } catch (e: Throwable) {
        // Network failures, and anything unexpected from the SDK on this device.
        throw AiFailure("Could not reach the AI service. Showing the on-device estimate.")
    }

    private fun textOf(message: BetaMessage): String {
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL)
            throw AiFailure("The AI declined to assess this. Showing the on-device estimate.")
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw AiFailure("The AI returned an empty answer. Showing the on-device estimate.")
        return text
    }

    private val schema: BetaJsonOutputFormat.Schema by lazy {
        val root = Json.parseToJsonElement(AiProtocol.schema) as JsonObject
        val b = BetaJsonOutputFormat.Schema.builder()
        for ((k, v) in root) b.putAdditionalProperty(k, JsonValue.from(plain(v)))
        b.build()
    }

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map { plain(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
    }

    private fun jpegBase64(source: Bitmap): String {
        // 768 px on the long side is plenty to identify an object and keeps the upload quick on mobile data.
        val scale = 768f / maxOf(source.width, source.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
