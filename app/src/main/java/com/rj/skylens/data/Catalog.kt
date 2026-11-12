package com.rj.skylens.data

import android.content.Context
import com.rj.skylens.astro.DEG
import com.rj.skylens.astro.Vec3

class Star(
    val hip: Int, val raHours: Double, val decDeg: Double, val mag: Float, val bv: Float,
    /** Proper names (Sirius, Vega...) are labelled; Bayer designations only when zoomed in. */
    val hasProperName: Boolean,
    val name: String
) {
    val j2000: Vec3 = Vec3.fromSpherical(raHours * 15.0 * DEG, decDeg * DEG)
}

class Constellation(val abbr: String, val name: String, val segments: IntArray /* pairs of star indices */) {
    /** Centre direction (J2000 unit vector) used to place the label. */
    lateinit var center: Vec3
}

class DeepSky(
    val label: String, val id: String, val type: String, val raDeg: Double, val decDeg: Double,
    val mag: Float, val sizeArcmin: Float, val commonName: String
) {
    val j2000: Vec3 = Vec3.fromSpherical(raDeg * DEG, decDeg * DEG)
    val isMessier get() = label.startsWith("M") && label.drop(1).all { it.isDigit() }
    val displayName get() = if (commonName.isNotEmpty()) "$label $commonName" else label
}

class Catalog(val stars: List<Star>, val constellations: List<Constellation>, val deepSky: List<DeepSky>) {
    companion object {
        fun load(context: Context): Catalog {
            val stars = ArrayList<Star>(5200)
            val byHip = HashMap<Int, Int>()
            context.assets.open("stars.csv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split(',', limit = 7)
                    if (p.size < 7) return@forEach
                    val s = Star(p[0].toInt(), p[1].toDouble(), p[2].toDouble(), p[3].toFloat(), p[4].toFloat(), p[5] == "1", p[6].trim())
                    if (s.hip != 0) byHip[s.hip] = stars.size
                    stars += s
                }
            }
            val cons = ArrayList<Constellation>(90)
            context.assets.open("constellations.csv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split(',', limit = 3)
                    if (p.size < 3) return@forEach
                    val idx = ArrayList<Int>()
                    p[2].split(' ').filter { it.contains('-') }.forEach { seg ->
                        val (a, b) = seg.split('-')
                        val ia = byHip[a.toInt()]; val ib = byHip[b.toInt()]
                        if (ia != null && ib != null) { idx += ia; idx += ib }
                    }
                    val c = Constellation(p[0], p[1], idx.toIntArray())
                    var sum = Vec3(0.0, 0.0, 0.0)
                    idx.distinct().forEach { sum += stars[it].j2000 }
                    c.center = sum.unit()
                    cons += c
                }
            }
            val dso = ArrayList<DeepSky>(600)
            context.assets.open("dso.csv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split(',', limit = 8)
                    if (p.size < 8) return@forEach
                    dso += DeepSky(p[0], p[1], p[2], p[3].toDouble(), p[4].toDouble(), p[5].toFloat(), p[6].toFloat(), p[7].trim())
                }
            }
            return Catalog(stars, cons, dso)
        }
    }
}
