package com.rj.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20260209
import java.time.Duration

/**
 * Claude-powered RJ. One conversation per drive: the system prompt (persona + profile + memories) stays
 * fixed and is prompt-cached; each segment appends a cue with the live context, and the full assistant
 * response is appended back so the show remembers what it already said.
 */
class ClaudeBrain(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val effort: OutputConfig.Effort = OutputConfig.Effort.LOW,
    private val location: Location? = null,
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .build(),
) : RjBrain {

    data class Location(val city: String?, val region: String?, val country: String?, val timezone: String?)

    private var system: String = ""
    private val messages = mutableListOf<MessageParam>()

    override fun startShow(persona: Persona, profile: UserProfile, memories: List<String>, catalog: List<SongPick>) {
        system = PromptBuilder.system(persona, profile, memories, catalog)
        messages.clear()
    }

    @Synchronized
    override fun next(cue: Cue, ctx: ShowContext): Segment {
        // Keep the history append-only; when the show gets long, start a fresh conversation instead of
        // trimming (the live context already carries what was played).
        if (messages.size > MAX_MESSAGES) messages.clear()

        messages.add(MessageParam.ofUser(PromptBuilder.cue(cue, ctx)))

        var response = create()
        messages.add(response.toParam())
        // Server-side tools (web search) can pause a long turn; resume it.
        var resumes = 0
        while (response.stopReason().orElse(null) == StopReason.PAUSE_TURN && resumes++ < 3) {
            response = create()
            messages.add(response.toParam())
        }

        if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
            return Segment(speech = "", song = null)
        }

        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return Segment.parse(text)
    }

    private fun create(): Message {
        val search = WebSearchTool20260209.builder().maxUses(3L)
        location?.let { loc ->
            search.userLocation(
                UserLocation.builder().apply {
                    loc.city?.let { city(it) }
                    loc.region?.let { region(it) }
                    loc.country?.let { country(it) }
                    loc.timezone?.let { timezone(it) }
                }.build()
            )
        }

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
            .messages(messages.toList())
            .addTool(search.build())
            .outputConfig(OutputConfig.builder().effort(effort).build())
            // Top-level cache breakpoint so the growing conversation history is reused turn to turn.
            .cacheControl(CacheControlEphemeral.builder().build())
            // If a safety classifier declines, let the API retry on a suitable fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        return client.messages().create(params)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val MAX_MESSAGES = 60
    }
}
