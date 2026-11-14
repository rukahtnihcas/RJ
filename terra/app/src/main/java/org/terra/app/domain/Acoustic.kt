package org.terra.app.domain

import org.terra.app.model.Species
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

data class AcousticFeatures(
    /** Strongest frequency, Hz. */
    val peakHz: Float,
    /** Band holding the central 80% of the sound's energy. */
    val lowHz: Float, val highHz: Float,
    /** Pulses (syllables) per second. */
    val rate: Float,
    /** 0 = hiss/buzz, 1 = pure whistle. */
    val tonality: Float,
    /** How far the loud moments stand above the background, in dB. */
    val contrastDb: Float,
    /** Overall level, dBFS. */
    val levelDb: Float
) {
    val tonal get() = tonality >= 0.45f
}

data class AcousticMatch(val species: Species, val score: Float)

/**
 * A deliberately transparent on-device listener. It measures pitch band, pulse rate and
 * tonality, and compares them with each species' published call characteristics.
 * It proposes candidates; the observer's ear confirms. No audio ever leaves the phone.
 */
object AcousticMatcher {
    const val FRAME = 1024
    const val HOP = 512
    private const val MIN_HZ = 120f

    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }

    /** Power spectrum (FRAME/2 bins) of one frame starting at [offset]. */
    fun spectrum(samples: FloatArray, offset: Int): FloatArray {
        val re = FloatArray(FRAME) { samples[offset + it] * window[it] }
        val im = FloatArray(FRAME)
        fft(re, im)
        return FloatArray(FRAME / 2) { re[it] * re[it] + im[it] * im[it] }
    }

    /** Returns null when the clip is too short or effectively silent. */
    fun analyze(samples: FloatArray, sampleRate: Int): AcousticFeatures? {
        val frames = (samples.size - FRAME) / HOP + 1
        if (frames < 8) return null
        val binHz = sampleRate.toFloat() / FRAME
        val minBin = (MIN_HZ / binHz).toInt() + 1
        val spectra = Array(frames) { spectrum(samples, it * HOP) }
        val energy = FloatArray(frames) { f -> var e = 0f; for (b in minBin until FRAME / 2) e += spectra[f][b]; e }
        val sorted = energy.sorted()
        val floor = sorted[frames / 10].coerceAtLeast(1e-12f)
        val top = sorted[frames * 9 / 10].coerceAtLeast(1e-12f)
        var rms = 0.0
        for (s in samples) rms += s * s
        val levelDb = (10 * ln(rms / samples.size + 1e-12) / ln(10.0)).toFloat()
        if (levelDb < -70f) return null
        val contrastDb = (10 * ln((top / floor).toDouble()) / ln(10.0)).toFloat()

        // "Active" frames carry the call; a steady drone makes every frame active.
        val threshold = if (contrastDb < 4f) 0f else sqrt(floor * top)
        val active = (0 until frames).filter { energy[it] >= threshold }
        val mean = FloatArray(FRAME / 2)
        var tonality = 0f
        for (f in active) {
            val s = spectra[f]
            var peak = minBin
            for (b in minBin until FRAME / 2) { mean[b] += s[b]; if (s[b] > s[peak]) peak = b }
            var near = 0f
            for (b in maxOf(minBin, peak - 2)..minOf(FRAME / 2 - 1, peak + 2)) near += s[b]
            tonality += near / energy[f].coerceAtLeast(1e-12f)
        }
        tonality /= active.size
        var total = 0f
        var peakBin = minBin
        for (b in minBin until FRAME / 2) { total += mean[b]; if (mean[b] > mean[peakBin]) peakBin = b }
        var acc = 0f
        var lowBin = minBin
        var highBin = FRAME / 2 - 1
        var lowSet = false
        for (b in minBin until FRAME / 2) {
            acc += mean[b]
            if (!lowSet && acc >= 0.1f * total) { lowBin = b; lowSet = true }
            if (acc >= 0.9f * total) { highBin = b; break }
        }

        // Pulse rate: count rising edges of the loudness envelope with hysteresis.
        var pulses = 0
        if (contrastDb >= 4f) {
            val up = sqrt(floor * top) * 1.5f
            val down = sqrt(floor * top) * 0.7f
            var on = false
            for (e in energy) {
                if (!on && e > up) { on = true; pulses++ } else if (on && e < down) on = false
            }
        }
        val seconds = samples.size.toFloat() / sampleRate
        return AcousticFeatures(peakBin * binHz, lowBin * binHz, highBin * binHz, pulses / seconds, tonality, contrastDb, levelDb)
    }

    private fun octaves(a: Float, b: Float) = abs(ln(a / b) / ln(2f))

    fun score(f: AcousticFeatures, s: Species): Float {
        val a = s.acoustic ?: return 0f
        val band = if (f.peakHz >= a.lowHz && f.peakHz <= a.highHz) 1f
        else exp(-3f * minOf(octaves(f.peakHz, a.lowHz.toFloat()), octaves(f.peakHz, a.highHz.toFloat())))
        // Narrow-band callers are more diagnostic than broadband ones.
        val width = octaves(a.highHz.toFloat(), a.lowHz.toFloat())
        val specificity = (1.02f - 0.12f * width).coerceIn(0.7f, 1f)
        val rate = when {
            f.rate >= a.rateMin && f.rate <= a.rateMax -> 1f
            f.rate < a.rateMin -> exp(-1.2f * (a.rateMin - f.rate) / (a.rateMin + 0.5f))
            else -> exp(-1.2f * (f.rate - a.rateMax) / (a.rateMax + 0.5f))
        }
        val tone = if (f.tonal == a.tonal) 1f else 0.55f
        return (band * specificity * rate * tone).coerceIn(0f, 1f)
    }

    fun rank(f: AcousticFeatures, candidates: List<Species>, min: Float = 0.3f, limit: Int = 8): List<AcousticMatch> =
        candidates.filter { it.acoustic != null }.map { AcousticMatch(it, score(f, it)) }
            .filter { it.score >= min }.sortedByDescending { it.score }.take(limit)

    fun describe(f: AcousticFeatures): String {
        val pitch = when {
            f.peakHz < 700 -> "low-pitched"
            f.peakHz < 2500 -> "mid-pitched"
            f.peakHz < 5000 -> "high-pitched"
            else -> "very high-pitched"
        }
        val texture = if (f.tonal) "clear, whistled" else "harsh or buzzy"
        val tempo = when {
            f.rate < 0.3f -> "steady"
            f.rate < 2.5f -> "slow, spaced notes"
            f.rate < 7f -> "brisk repeated notes"
            else -> "rapid trill"
        }
        return "A $pitch, $texture sound around ${(f.peakHz / 100).toInt() / 10f} kHz — $tempo."
    }

    fun toFloat(pcm: ShortArray, n: Int = pcm.size) = FloatArray(n) { pcm[it] / 32768f }
}
