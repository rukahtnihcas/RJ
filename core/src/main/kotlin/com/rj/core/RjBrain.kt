package com.rj.core

/** The thing that writes the show. Swappable so the engine can be tested without the network. */
interface RjBrain {
    /** Start a new show (new drive). */
    fun startShow(persona: Persona, profile: UserProfile, memories: List<String>)

    /** Produce the next on-air segment. Blocking; call from a background dispatcher. */
    fun next(cue: Cue, ctx: ShowContext): Segment
}

/** Offline fallback so the show never goes silent when the network or API key is missing. */
class FallbackBrain : RjBrain {
    private var name = "friend"
    private var car = "your car"
    private var i = 0

    override fun startShow(persona: Persona, profile: UserProfile, memories: List<String>) {
        name = profile.name.ifBlank { "friend" }; car = persona.carName; i = 0
    }

    override fun next(cue: Cue, ctx: ShowContext): Segment {
        i++
        val speech = when (cue.type) {
            CueType.OPENER -> "Good ${ShowContext.partOfDay(ctx.now)}, $name! It's $car, and I'm running on backup " +
                "brains right now, so no news or chat until we're back online. Let's keep the music going."
            CueType.REPLY -> "I can't quite think straight without a connection, $name. Back to the music."
            else -> listOf("Keep it rolling.", "More music coming up.", "Here's another one for the road.")[i % 3]
        }
        return Segment(speech = speech, song = null)
    }
}
