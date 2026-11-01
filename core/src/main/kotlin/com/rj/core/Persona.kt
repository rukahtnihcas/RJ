package com.rj.core

/** The character your car plays. */
data class Persona(
    val carName: String = "Nova",
    val style: Style = Style.MORNING_HOST,
    /** Optional extra flavour the user writes themselves ("obsessed with cricket stats", "dad jokes"). */
    val quirks: String = "",
) {
    enum class Style(val label: String, val description: String, val speechRate: Float, val pitch: Float) {
        MORNING_HOST(
            "Breakfast-show host",
            "high-energy, warm, quick-witted breakfast radio host who makes the commute feel like a party",
            1.05f, 1.05f,
        ),
        LATE_NIGHT(
            "Late-night smooth",
            "velvet-voiced late-night host: unhurried, thoughtful, a little poetic, great at mood and storytelling",
            0.92f, 0.92f,
        ),
        SIDEKICK(
            "Cheeky sidekick",
            "cheeky, playful best-friend sidekick who teases lovingly and hypes the driver up",
            1.08f, 1.1f,
        ),
        CURATOR(
            "Music nerd curator",
            "deeply knowledgeable music curator who shares the stories behind songs and makes surprising connections",
            1.0f, 1.0f,
        ),
        CALM_COPILOT(
            "Calm co-pilot",
            "calm, reassuring co-pilot who keeps things light, low-stress and focused on the road",
            0.95f, 0.97f,
        ),
    }
}
