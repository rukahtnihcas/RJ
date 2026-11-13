package earth.ripple.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs

@Serializable
data class Pledge(
    val objectName: String,
    val action: String,
    val co2KgPerYear: Double,
    val waterLPerYear: Double,
    val day: String,
    val done: Boolean = false,
)

@Serializable
data class Profile(
    val points: Int = 0,
    val scans: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val lastDay: String = "",
    val seen: Set<String> = emptySet(),
    val guesses: Int = 0,
    val correct: Int = 0,
    val living: Int = 0,
    val barcodes: Int = 0,
    val shares: Int = 0,
    val questDay: String = "",
    val badges: Set<String> = emptySet(),
    val pledges: List<Pledge> = emptyList(),
) {
    val co2PledgedKg: Double get() = pledges.sumOf { it.co2KgPerYear }
    val waterPledgedL: Double get() = pledges.sumOf { it.waterLPerYear }
}

data class Badge(val id: String, val glyph: String, val name: String, val how: String)

data class Quest(val id: String, val text: String, val test: (Assessment, Boolean) -> Boolean)

data class Level(val index: Int, val name: String, val glyph: String, val floor: Int, val next: Int?)

/** Something worth telling the user about: points earned, a badge, a finished quest. */
data class Reward(val points: Int, val text: String, val badge: Badge? = null)

data class Outcome(val profile: Profile, val rewards: List<Reward>)

/**
 * The game layer. Its purpose is to make people look at one more thing, and to reward understanding
 * and action more than volume: a correct guess or a pledge is worth more than a scan.
 */
object Game {
    val badges: List<Badge> = listOf(
        Badge("first", "✦", "First light", "Scan your first object"),
        Badge("ten", "❖", "Ten things", "Scan ten different kinds of object"),
        Badge("thirsty", "💧", "Hidden water", "Find something that scores −3.5 or worse on freshwater"),
        Badge("giver", "✚", "Giver", "Find something whose overall score is positive"),
        Badge("life", "❀", "Life spotter", "Scan three living things"),
        Badge("eye", "◉", "Sharp eye", "Guess the standout impact right five times"),
        Badge("swap", "⇄", "First swap", "Commit to your first better choice"),
        Badge("tonne", "☁", "Tonne down", "Pledge swaps worth a tonne of CO₂ a year"),
        Badge("detective", "▥", "Label detective", "Scan a product by its barcode"),
        Badge("streak3", "☀", "Three in a row", "Scan on three days running"),
        Badge("streak7", "☼", "A full week", "Scan on seven days running"),
        Badge("voice", "➶", "Ripple maker", "Share three discoveries"),
    )

    private val levels = listOf(
        Triple("Seed", "·", 0), Triple("Sprout", "⚘", 60), Triple("Sapling", "❦", 200),
        Triple("Tree", "♣", 500), Triple("Grove", "♧", 1000), Triple("Forest", "❋", 2000),
    )

    fun level(points: Int): Level {
        val i = levels.indexOfLast { points >= it.third }
        val (name, glyph, floor) = levels[i]
        return Level(i, name, glyph, floor, levels.getOrNull(i + 1)?.third)
    }

    private val quests: List<Quest> = listOf(
        Quest("nature+", "Find something that is good for nature (Nature above 0)") { a, _ -> a.score(Dimension.NATURE).score > 0 },
        Quest("thirsty", "Find the thirstiest thing in your kitchen (Freshwater −3 or worse)") { a, _ -> a.score(Dimension.WATER).score <= -3 },
        Quest("wear", "Scan something you are wearing") { a, _ -> a.kind == Kind.FASHION },
        Quest("alive", "Find a living thing that isn't human") { a, _ -> a.kind == Kind.LIVING && a.id != "human" },
        Quest("social+", "Find something that is good for people (Social +2 or better)") { a, _ -> a.score(Dimension.SOCIAL).score >= 2 },
        Quest("barcode", "Scan a packaged food by its barcode") { _, barcode -> barcode },
        Quest("heavy", "Find a climate heavyweight (Carbon −4 or worse)") { a, _ -> a.score(Dimension.CARBON).score <= -4 },
        Quest("health+", "Find something that is good for your health (Health +3 or better)") { a, _ -> a.score(Dimension.HEALTH).score >= 3 },
        Quest("neutral", "Find something almost harmless (no score worse than −1)") { a, _ -> a.values.all { it >= -1 } && !a.generic },
        Quest("plug", "Scan something with a plug or a battery") { a, _ -> a.kind == Kind.ELECTRONICS },
    )

    /** One quest a day, the same for everyone, so friends can compare. */
    fun quest(day: String): Quest = quests[(LocalDate.parse(day).toEpochDay() % quests.size).toInt()]

    fun onScan(p: Profile, a: Assessment, day: String, viaBarcode: Boolean): Outcome {
        val rewards = mutableListOf<Reward>()
        var points = 0
        val isNew = a.id !in p.seen && !a.generic
        if (a.id != "unknown") {
            points += 10; rewards += Reward(10, "Scanned")
            if (isNew) { points += 15; rewards += Reward(15, "New kind of thing") }
        }

        val streak = when {
            p.lastDay == day -> p.streak
            p.lastDay.isNotEmpty() && LocalDate.parse(p.lastDay).plusDays(1) == LocalDate.parse(day) -> p.streak + 1
            else -> 1
        }
        if (streak > p.streak && streak > 1) { points += 5 * streak.coerceAtMost(7); rewards += Reward(5 * streak.coerceAtMost(7), "$streak-day streak") }

        var questDay = p.questDay
        if (p.questDay != day && quest(day).test(a, viaBarcode)) {
            questDay = day; points += 40; rewards += Reward(40, "Daily quest complete")
        }

        var next = p.copy(
            points = p.points + points,
            scans = p.scans + 1,
            streak = streak,
            bestStreak = maxOf(p.bestStreak, streak),
            lastDay = day,
            seen = if (a.generic) p.seen else p.seen + a.id,
            living = p.living + if (a.kind == Kind.LIVING) 1 else 0,
            barcodes = p.barcodes + if (viaBarcode) 1 else 0,
            questDay = questDay,
        )
        next = award(next, rewards, a)
        return Outcome(next, rewards)
    }

    /**
     * A better source (product database, AI) has replaced the first on-device answer for a scan that
     * was already counted. Only what the better answer newly proves is rewarded.
     */
    fun onRefine(p: Profile, a: Assessment, day: String, viaBarcode: Boolean, firstWasGeneric: Boolean): Outcome {
        val rewards = mutableListOf<Reward>()
        var points = 0
        val isNew = firstWasGeneric && a.id !in p.seen && !a.generic
        if (isNew) { points += 15; rewards += Reward(15, "New kind of thing") }
        var questDay = p.questDay
        if (p.questDay != day && quest(day).test(a, viaBarcode)) {
            questDay = day; points += 40; rewards += Reward(40, "Daily quest complete")
        }
        val next = p.copy(
            points = p.points + points,
            seen = if (a.generic) p.seen else p.seen + a.id,
            barcodes = p.barcodes + if (viaBarcode) 1 else 0,
            questDay = questDay,
        )
        return Outcome(award(next, rewards, a), rewards)
    }

    /** The guessing game: before the reveal, which dimension does this object affect most? */
    fun isCorrectGuess(a: Assessment, guess: Dimension): Boolean {
        val top = a.values.maxOf { abs(it) }
        return top - abs(a.score(guess).score) <= 0.5
    }

    fun onGuess(p: Profile, a: Assessment, guess: Dimension): Outcome {
        val right = isCorrectGuess(a, guess)
        val rewards = mutableListOf<Reward>()
        if (right) rewards += Reward(20, "Good instinct")
        val next = award(p.copy(points = p.points + if (right) 20 else 0, guesses = p.guesses + 1, correct = p.correct + if (right) 1 else 0), rewards, a)
        return Outcome(next, rewards)
    }

    fun onPledge(p: Profile, a: Assessment, alt: Alternative, day: String): Outcome {
        if (p.pledges.any { it.objectName == a.name && it.action == alt.name }) return Outcome(p, emptyList())
        val rewards = mutableListOf(Reward(25, "Committed to a better choice"))
        val pledge = Pledge(a.name, alt.name, alt.co2KgPerYear, alt.waterLPerYear, day)
        val next = award(p.copy(points = p.points + 25, pledges = p.pledges + pledge), rewards, a)
        return Outcome(next, rewards)
    }

    fun onShare(p: Profile): Outcome {
        val rewards = mutableListOf(Reward(10, "Shared"))
        val next = award(p.copy(points = p.points + 10, shares = p.shares + 1), rewards, null)
        return Outcome(next, rewards)
    }

    private fun award(p: Profile, rewards: MutableList<Reward>, a: Assessment?): Profile {
        val earned = buildSet {
            if (p.scans >= 1) add("first")
            if (p.seen.size >= 10) add("ten")
            if (a != null && a.score(Dimension.WATER).score <= -3.5 && !a.generic) add("thirsty")
            if (a != null && a.average > 0 && !a.generic) add("giver")
            if (p.living >= 3) add("life")
            if (p.correct >= 5) add("eye")
            if (p.pledges.isNotEmpty()) add("swap")
            if (p.co2PledgedKg >= 1000) add("tonne")
            if (p.barcodes >= 1) add("detective")
            if (p.bestStreak >= 3) add("streak3")
            if (p.bestStreak >= 7) add("streak7")
            if (p.shares >= 3) add("voice")
        } - p.badges
        if (earned.isEmpty()) return p
        var bonus = 0
        for (id in earned) {
            val b = badges.first { it.id == id }
            bonus += 30
            rewards += Reward(30, "Badge: ${b.name}", b)
        }
        return p.copy(badges = p.badges + earned, points = p.points + bonus)
    }

    /** Everyday equivalents that make a pledge total feel real. */
    fun co2Equivalent(kg: Double): String = when {
        kg <= 0 -> ""
        kg < 50 -> "like skipping ${(kg / 0.17).toInt()} km of driving"
        kg < 1000 -> (kg / 4600 * 52).toInt().coerceAtLeast(1).let { "like taking a car off the road for $it week${if (it == 1) "" else "s"}" }
        else -> "like ${"%.1f".format(kg / 1500)} return flights across the Atlantic"
    }

    fun waterEquivalent(l: Double): String = when {
        l <= 0 -> ""
        l < 1500 -> "${(l / 70).toInt().coerceAtLeast(1)} showers' worth"
        l < 500_000 -> "${(l / 150).toInt()} full bathtubs"
        else -> "${"%.1f".format(l / 2_500_000)} Olympic swimming pools"
    }
}
