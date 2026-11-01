package com.rj.core

import java.text.Normalizer

/** Judges spoken quiz answers locally, so there's no network round trip between answer and verdict. */
object QuizJudge {
    private val letters = listOf("a", "b", "c", "d")
    private val ordinals = listOf(
        listOf("first", "one", "1"), listOf("second", "two", "2"),
        listOf("third", "three", "3"), listOf("fourth", "four", "4"),
    )
    private val filler = setOf("the", "a", "an", "of", "is", "it", "its", "i", "think", "um", "uh", "er", "maybe",
        "answer", "option", "and", "to", "in", "on", "by", "my", "guess", "say", "go", "with", "that", "s")

    fun spokenQuestion(q: QuizQuestion): String = buildString {
        append(q.question.trim())
        if (q.choices.size >= 2) {
            append(" ")
            q.choices.take(4).forEachIndexed { i, c ->
                if (i == q.choices.size.coerceAtMost(4) - 1) append("or ")
                append("${letters[i].uppercase()}: ${c.trim().trimEnd('.', '?')}")
                append(if (i == q.choices.size.coerceAtMost(4) - 1) "?" else ", ")
            }
        }
    }

    fun isCorrect(heard: String, q: QuizQuestion): Boolean {
        val said = normalize(heard)
        if (said.isBlank()) return false
        val words = said.split(' ').filter { it.isNotEmpty() }

        // "B", "option b", "the second one"
        val correctIndex = q.choices.indexOfFirst { normalize(it) == normalize(q.answer) }
        if (correctIndex in 0..3) {
            val picked = pickedIndex(words, q.choices.size)
            if (picked != null) return picked == correctIndex
        }

        val targets = (listOf(q.answer) + q.accept).map(::normalize).filter { it.isNotBlank() }
        // Mentions a wrong choice but not the right one -> wrong.
        return targets.any { t -> matches(said, t) }
    }

    private fun pickedIndex(words: List<String>, n: Int): Int? {
        // Only treat a bare letter as a choice when the whole answer is short ("b", "option b", "it's b").
        val content = words.filterNot { it in filler || it == "one" }
        if (content.size == 1) {
            val i = letters.indexOf(content[0])
            if (i in 0 until n) return i
        }
        if (words.size <= 4) {
            ordinals.forEachIndexed { i, forms -> if (i < n && words.any { it in forms.take(1) }) return i }
        }
        return null
    }

    private fun matches(said: String, target: String): Boolean {
        if (said == target || said.contains(target)) return true
        val tWords = target.split(' ').filter { it !in filler && it.isNotEmpty() }
        if (tWords.isEmpty()) return false
        val sWords = said.split(' ').toSet()
        fun hit(tw: String) = sWords.any { sw -> sw == tw || (tw.length >= 5 && editDistance(sw, tw) <= 1) }
        val hits = tWords.count(::hit)
        // Numbers must match exactly. Names: at least half the words, including the most distinctive
        // (longest) one, so "Mercury" counts for "Freddie Mercury" but "Freddie" alone doesn't.
        return if (tWords.any { w -> w.all(Char::isDigit) }) hits == tWords.size
        else hits >= (tWords.size + 1) / 2 && hit(tWords.asReversed().maxBy { it.length }) // ties go to the last word (surname)
    }

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noAccents.replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

/** Routes things the driver says to show features, so "quiz me" works instantly (and offline). */
object IntentRouter {
    fun route(utterance: String): CueType? {
        val s = QuizJudge.normalize(utterance)
        fun has(vararg w: String) = w.any { Regex("\\b$it\\b").containsMatchIn(s) }
        return when {
            has("quiz", "quiz me", "test me", "ask me a question", "play a game", "name that tune") -> CueType.QUIZ
            has("podcast", "debate", "discussion", "discuss") -> CueType.PODCAST
            has("trivia", "fun fact", "tell me about this place", "where are we", "tell me about here") -> CueType.TRIVIA
            has("news", "headlines", "what s happening", "whats happening") -> CueType.NEWS_BREAK
            has("joke", "make me laugh") -> CueType.JOKE
            else -> null
        }
    }
}
