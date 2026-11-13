package earth.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** An AI service other than Claude, reached through the widely copied OpenAI "chat completions" API. */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    /** Where a person gets a key, shown in Settings. */
    val keyUrl: String,
    val note: String,
) {
    val isClaude: Boolean get() = id == CLAUDE.id

    companion object {
        val CLAUDE = Provider("claude", "Claude (Anthropic)", "", AiProtocol.DEFAULT_MODEL, "console.anthropic.com",
            "Paid per use. The most thorough answers.")
        val KIMI = Provider("kimi", "Kimi K2.6 via OpenRouter (free)", "https://openrouter.ai/api/v1", "moonshotai/kimi-k2.6:free",
            "openrouter.ai/keys", "Free with a free OpenRouter account. Rate-limited, and free models can be busy or withdrawn.")
        val GEMINI = Provider("gemini", "Google Gemini (free tier)", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-latest", "aistudio.google.com/apikey",
            "Free tier with daily limits. Google may use free-tier requests to improve its products.")
        val CUSTOM = Provider("custom", "Other OpenAI-compatible service", "", "", "",
            "Any service with an OpenAI-style chat completions endpoint and an image-capable model.")

        val all = listOf(KIMI, GEMINI, CLAUDE, CUSTOM)
        fun byId(id: String?): Provider = all.firstOrNull { it.id == id } ?: CLAUDE
    }
}

/**
 * Request bodies and response parsing for OpenAI-compatible services. Services differ in how well
 * they follow a requested output format, so assessment requests come in three strengths and the
 * caller steps down when a service rejects one.
 */
object OpenAiCompat {
    enum class Format { SCHEMA, JSON, NONE }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The system prompt for services that may ignore the schema: the schema is spelled out in it as well. */
    val assessSystem: String = AiProtocol.systemPrompt + "\n\nOUTPUT\nReply with ONLY one JSON object, no prose and no code fences, " +
        "that matches this JSON Schema exactly (every field present):\n" + AiProtocol.schema

    fun assessBody(model: String, userText: String, jpegBase64: String, format: Format): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 8000)
        put("temperature", 0.2)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", assessSystem) })
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", userText) })
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$jpegBase64") }
                    })
                }
            })
        }
        when (format) {
            Format.SCHEMA -> putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "ripple_assessment")
                    put("strict", true)
                    put("schema", json.parseToJsonElement(AiProtocol.schema))
                }
            }
            Format.JSON -> putJsonObject("response_format") { put("type", "json_object") }
            Format.NONE -> Unit
        }
    }.toString()

    fun chatBody(model: String, about: Assessment, history: List<Pair<String, String>>, question: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 1500)
        put("temperature", 0.4)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", AiProtocol.chatSystemPrompt + "\n\nASSESSMENT\n" + AiProtocol.chatContext(about)) })
            for ((q, a) in history) {
                add(buildJsonObject { put("role", "user"); put("content", q) })
                add(buildJsonObject { put("role", "assistant"); put("content", a) })
            }
            add(buildJsonObject { put("role", "user"); put("content", question) })
        })
    }.toString()

    sealed interface Reply {
        data class Text(val text: String) : Reply
        data class Refused(val reason: String) : Reply
        data class Error(val message: String) : Reply
    }

    /** Pulls the answer out of a chat completions response, or explains why there isn't one. */
    fun reply(body: String): Reply {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return Reply.Error("Unreadable response")
        (root["error"] as? JsonObject)?.let { return Reply.Error(str(it["message"]) ?: "The service returned an error") }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Reply.Error("No answer in the response")
        val message = choice["message"] as? JsonObject
        str(message?.get("refusal"))?.takeIf { it.isNotBlank() }?.let { return Reply.Refused(it) }
        val content = when (val c = message?.get("content")) {
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.let { p -> str(p["text"]) } }.joinToString("")
            else -> null
        }
        if (content.isNullOrBlank()) {
            return if (str(choice["finish_reason"]) == "length") Reply.Error("The answer was cut off") else Reply.Error("The service returned an empty answer")
        }
        return Reply.Text(stripThinking(content))
    }

    /** Some free models write their reasoning inline before the answer. */
    fun stripThinking(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "").trim()

    /** The JSON object inside a reply that may carry prose or code fences around it. */
    fun jsonObjectIn(text: String): String? {
        val t = stripThinking(text)
        val start = t.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inString = false; var escaped = false
        for (i in start until t.length) {
            val c = t[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return t.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull
}
