package com.iaido.core.perf

import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic swipe motion model shared by the opt-in accuracy and segmentation benchmarks (not recorded user data). */
internal object SyntheticSwipes {
    fun synthesize(layout: KeyboardLayout, word: String, sigma: Double, rng: Random, tags: MutableSet<String>, variant: String? = null): GesturePath {
        // 1. waypoints: collapse doubles into a dwell, occasionally skip a sloppy middle letter.
        class Way(var x: Double, var y: Double, var dwell: Boolean)
        val ways = ArrayList<Way>()
        var skipped = false
        for ((i, ch) in word.withIndex()) {
            val c = layout.centerOf(ch)
            if (i in 1 until word.length - 1 && word.length >= 5 && rng.nextDouble() < 0.05) { skipped = true; continue }
            if (ways.isNotEmpty() && i > 0 && word[i - 1] == ch) { ways.last().dwell = true; continue }
            ways += Way(c.x.toDouble(), c.y.toDouble(), false)
        }
        if (skipped) tags += "skip"
        // 2. corner cutting / overshoot on interior waypoints (computed from the ideal neighbours).
        val ideal = ways.map { doubleArrayOf(it.x, it.y) }
        for (k in 1 until ways.size - 1) {
            val mx = (ideal[k - 1][0] + ideal[k + 1][0]) / 2; val my = (ideal[k - 1][1] + ideal[k + 1][1]) / 2
            val roll = rng.nextDouble()
            val f = when {
                roll < 0.55 -> rng.nextDouble() * 0.30          // cut the corner
                roll < 0.70 -> -rng.nextDouble() * 0.25         // overshoot
                else -> 0.0
            }
            ways[k].x += (mx - ideal[k][0]) * f; ways[k].y += (my - ideal[k][1]) * f
        }
        // 2b. endpoint slop: in ~30% of swipes the finger lands/lifts short of the first/last key centre.
        if (ways.size >= 2) {
            if (rng.nextDouble() < 0.30) {
                val f = rng.nextDouble() * 0.25
                ways[0].x += (ways[1].x - ways[0].x) * f; ways[0].y += (ways[1].y - ways[0].y) * f
            }
            if (rng.nextDouble() < 0.30) {
                val l = ways.size - 1; val f = rng.nextDouble() * 0.25
                ways[l].x += (ways[l - 1].x - ways[l].x) * f; ways[l].y += (ways[l - 1].y - ways[l].y) * f
            }
        }
        // 2c. wrong start / stray tail: an extra hop before the first letter and/or after the last.
        if (variant != null) {
            val f = ways.first()
            val side = if (rng.nextBoolean()) 1.0 else -1.0
            if (variant.startsWith("neighbor")) ways.add(0, Way(f.x + side, f.y, false))
            if (variant == "wrongrow") ways.add(0, Way(f.x + 0.5 * side, f.y + (if (rng.nextBoolean()) 1.0 else -1.0), false))
            if (variant == "numrow") ways.add(0, Way(f.x + rng.nextGaussian() * 0.3, -1.0, false))
            if (variant.endsWith("trail")) {
                val l = ways.last()
                val dir = rng.nextInt(4)
                val dx = if (dir == 0) 1.0 else if (dir == 1) -1.0 else 0.0
                val dy = if (dir == 2) 1.0 else if (dir == 3) -1.0 else 0.0
                ways += Way(l.x + dx, l.y + dy, false)
            }
            tags += "wrong-start"
        }
        // 3. densify with eased, speed-varying spacing; dwell adds clustered (or looped) points.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        xs += ways[0].x; ys += ways[0].y
        fun dwell(w: Way) {
            val loop = rng.nextDouble() < 0.25
            val n = 4 + rng.nextInt(5)
            for (j in 1..n) {
                if (loop) {
                    val a = 2 * PI * j / n
                    xs += w.x + 0.22 * kotlin.math.cos(a); ys += w.y + 0.22 * sin(a)
                } else { xs += w.x; ys += w.y }
            }
        }
        if (ways[0].dwell) dwell(ways[0])
        for (k in 1 until ways.size) {
            val a = ways[k - 1]; val b = ways[k]
            val n = 8 + rng.nextInt(8)
            val amp = -0.6 + rng.nextDouble() * 1.5
            for (j in 1..n) {
                val u = j.toDouble() / n
                val t = u - amp * sin(2 * PI * u) / (2 * PI)
                xs += a.x + (b.x - a.x) * t; ys += a.y + (b.y - a.y) * t
            }
            if (b.dwell) dwell(b)
        }
        // 4. noise: constant finger offset + AR(1) correlated per-point jitter.
        val rho = 0.6; val inno = sqrt(1 - rho * rho)
        val ox = rng.nextGaussian() * sigma * 0.4; val oy = rng.nextGaussian() * sigma * 0.4
        var ex = 0.0; var ey = 0.0
        val pts = ArrayList<GesturePoint>(xs.size)
        for (i in xs.indices) {
            ex = rho * ex + inno * rng.nextGaussian() * sigma
            ey = rho * ey + inno * rng.nextGaussian() * sigma
            pts += GesturePoint((xs[i] + ox + ex).toFloat(), (ys[i] + oy + ey).toFloat(), i * 8L)
        }
        return GesturePath(pts)
    }
}
