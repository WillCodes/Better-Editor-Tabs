package dev.willcodes.bettereditortabs.tabgroups.model.strip

import kotlin.math.roundToInt

/** Length of a full collapse or expand animation, in milliseconds. */
const val COLLAPSE_ANIMATION_MS = 150L

/** Cubic ease-out: fast at first, slowing towards the end. [t] is clamped to 0..1. */
fun easeOut(t: Double): Double {
    val c = t.coerceIn(0.0, 1.0)
    val r = 1.0 - c
    return 1.0 - r * r * r
}

/**
 * One collapse / expand animation of a group, as pure data. The *extent* is how much of the group's tabs is shown:
 * 1.0 = fully shown, 0.0 = gone. A collapse runs 1 to 0, an expand 0 to 1. Times are nanoseconds on any monotonic clock.
 */
data class CollapseTween(val from: Double, val to: Double, val startNanos: Long, val durationNanos: Long) {
    /** True if this animation shrinks the tabs. */
    val collapsing: Boolean get() = to < from

    private fun progress(now: Long): Double =
        if (durationNanos <= 0L) 1.0 else ((now - startNanos).toDouble() / durationNanos).coerceIn(0.0, 1.0)

    /** The extent at [now] (eased). */
    fun extentAt(now: Long): Double = if (durationNanos <= 0L) to else from + (to - from) * easeOut(progress(now))

    fun isDone(now: Long): Boolean = durationNanos <= 0L || now - startNanos >= durationNanos

    /**
     * The animation that takes over when the user flips the group at [now]: from the current extent to the other end, so it goes on
     * from where this one is instead of restarting. It takes [fullDurationNanos] times the distance it still has to cover (a reversal
     * at 30% of the way takes about 30% of the time).
     */
    fun reversed(now: Long, fullDurationNanos: Long): CollapseTween {
        val current = extentAt(now)
        val target = if (collapsing) 1.0 else 0.0
        return CollapseTween(current, target, now, (fullDurationNanos * kotlin.math.abs(target - current)).toLong())
    }

    companion object {
        /** A collapse (1 to 0) or an expand (0 to 1) starting at [now]. */
        fun start(collapsing: Boolean, now: Long, fullDurationNanos: Long): CollapseTween =
            if (collapsing) CollapseTween(1.0, 0.0, now, fullDurationNanos) else CollapseTween(0.0, 1.0, now, fullDurationNanos)
    }
}

/** [natural] scaled by [extent] (clamped to 0..1), rounded, never negative. */
fun animatedLength(natural: Int, extent: Double): Int = (maxOf(natural, 0) * extent.coerceIn(0.0, 1.0)).roundToInt()
