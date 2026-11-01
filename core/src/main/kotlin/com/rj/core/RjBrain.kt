package com.rj.core

/** The thing that writes the show. Swappable so the engine can be tested without the network. */
interface RjBrain {
    /**
     * Start a new show (new drive).
     * @param catalog songs the RJ may pick from (the phone's library); empty means "any real song".
     */
    fun startShow(persona: Persona, profile: UserProfile, memories: List<String>, catalog: List<SongPick> = emptyList())

    /** Produce the next on-air segment. Blocking; call from a background dispatcher. */
    fun next(cue: Cue, ctx: ShowContext): Segment
}
