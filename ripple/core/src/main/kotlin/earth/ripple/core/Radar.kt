package earth.ripple.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry of the five-axis chart, in unit coordinates (centre 0,0; outer ring radius 1).
 * Shared by the on-screen chart and the share image so they can never disagree.
 */
object Radar {
    /** −5 does not collapse to a point: the innermost ring keeps some size so the shape stays readable. */
    const val INNER = 0.14

    /** Radius of the zero ring: "no impact". Inside it is harm, outside it is benefit. */
    val ZERO: Double = radius(0.0)

    fun radius(score: Double): Double = INNER + (1 - INNER) * (Rubric.clamp(score) + 5.0) / 10.0

    /** Axis directions start at 12 o'clock and go clockwise. Screen coordinates: y grows downward. */
    fun angle(axis: Int): Double = -PI / 2 + axis * 2 * PI / 5

    fun point(axis: Int, r: Double): Pair<Double, Double> = Pair(r * cos(angle(axis)), r * sin(angle(axis)))

    fun polygon(scores: List<Double>): List<Pair<Double, Double>> =
        scores.mapIndexed { i, s -> point(i, radius(s)) }
}
