package app.styli.ink

import kotlin.math.exp
import kotlin.math.hypot

/**
 * The glide after a finger lets go of a scroll. The speed dies away smoothly (exponentially), and each step moves
 * by the exact distance covered in that time, so the page travels the same total distance at any frame rate.
 * Pure Kotlin; the view feeds it the time between frames.
 */
class Fling private constructor(private var vx: Double, private var vy: Double, private val tau: Double) {
    /** Slow enough to stop. */
    val done get() = hypot(vx, vy) < STOP

    /** How far to move (in pixels) over the next [dt] seconds. */
    fun step(dt: Double): Pair<Double, Double> {
        val k = exp(-dt / tau)
        val f = tau * (1 - k)
        val move = vx * f to vy * f
        vx *= k
        vy *= k
        return move
    }

    /** The page hit an edge sideways, or up and down: that speed is gone. */
    fun stopX() { vx = 0.0 }
    fun stopY() { vy = 0.0 }

    companion object {
        /** The speed (pixels per second) falls to 1/e in this many seconds, at the normal intensity. */
        const val TAU = 0.35

        /** A release slower than this (pixels per second) does not glide. */
        const val START = 150.0

        /** A glide slower than this (pixels per second) has ended. */
        const val STOP = 30.0

        /**
         * The glide for a release at (vx, vy) pixels per second, or null if too slow to glide. [intensity] scales how
         * far it carries (1 is normal, 2 twice as far, 0 or less does not glide at all).
         */
        fun start(vx: Double, vy: Double, intensity: Double = 1.0): Fling? =
            if (intensity > 0 && hypot(vx, vy) >= START) Fling(vx, vy, TAU * intensity) else null
    }
}
