package ai.hermes.bots.ui.avatar.blobatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Motion policy selected by app state, kept separate from deterministic layout. */
enum class BlobatarMotionMode {
    Static,
    Ambient,
    Held,
    OneShot,
}

/** Seeded timing and gaze values translated from upstream generation-2 motion.ts. */
data class BlobatarMotionSeeds(
    val phase: Long,
    val bob: Long,
    val blink: Long,
    val blinkPhase: Long,
    val saccade: Long,
    val saccadePhase: Long,
    val lookX: Double,
    val lookY: Double,
    val lookMX: Double,
    val lookMY: Double,
)

/** The signed foreshortening coefficients used while the eyes glance. */
data class BlobatarMotionWrap(
    val mx: Double,
    val side: Double,
    val sy: Double,
    val rotation: Double,
)

/**
 * Renderer-neutral motion frame.  [breathe] is a pair of scale factors, [blink] is 1 when eyes
 * are open and 0.08 at the narrowest blink, and all offsets use the 100-unit Blobatar space.
 */
data class BlobatarMotionFrame(
    val shake: BlobatarPoint = BlobatarPoint(0.0, 0.0),
    val breathe: BlobatarPoint = BlobatarPoint(1.0, 1.0),
    val bob: Double = 0.0,
    val saccade: BlobatarPoint = BlobatarPoint(0.0, 0.0),
    val rockPhase: Double = 1.0,
    val blink: Double = 1.0,
    val wrap: BlobatarMotionWrap = BlobatarMotionWrap(0.0, 0.0, 0.0, 0.0),
    val progress: Double = 0.0,
) {
    /** Compatibility/readability aliases for callers that only need basic transforms. */
    val bodyScaleX: Double get() = breathe.x
    val bodyScaleY: Double get() = breathe.y
    val bodyOffsetX: Double get() = shake.x
    val bodyOffsetY: Double get() = bob
    val eyeOffsetX: Double get() = saccade.x
    val eyeOffsetY: Double get() = saccade.y
    val isBlinking: Boolean get() = blink < 1.0
}

/** Resolve seeded timing values exactly once per avatar and reuse them for every frame. */
fun blobatarMotionSeeds(seed: String): BlobatarMotionSeeds {
    val traits = BlobatarTraits(seed)
    val blink = traits.number("motion.blink", 3_500.0, 6_500.0).roundToInt().toLong()
    val saccade = traits.number("motion.saccade", 4_200.0, 7_600.0).roundToInt().toLong()
    val lookX = traits.number("motion.lookX", 1.0, 2.2).let { if (traits.boolean("motion.lookXFlip")) -it else it }
    val lookY = traits.number("motion.lookY", 0.8, 1.7).let { if (traits.boolean("motion.lookYFlip")) -it else it }
    fun round2(value: Double) = (value * 100.0).roundToInt() / 100.0
    return BlobatarMotionSeeds(
        phase = traits.number("motion.phase", 0.0, 2_800.0).roundToInt().toLong(),
        bob = traits.number("motion.bob", 0.0, 3_400.0).roundToInt().toLong(),
        blink = blink,
        blinkPhase = traits.number("motion.blinkPhase", 0.0, blink.toDouble()).roundToInt().toLong(),
        saccade = saccade,
        saccadePhase = traits.number("motion.saccadePhase", 0.0, saccade.toDouble()).roundToInt().toLong(),
        lookX = round2(lookX),
        lookY = round2(lookY),
        lookMX = round2(abs(lookX)),
        lookMY = round2(abs(lookY)),
    )
}

/** Alias matching the upstream terminology. */
fun resolveBlobatarMotionSeeds(seed: String): BlobatarMotionSeeds = blobatarMotionSeeds(seed)

/** Upstream-style shorthand retained for framework-neutral callers. */
fun motionSeeds(seed: String): BlobatarMotionSeeds = blobatarMotionSeeds(seed)

/**
 * Resolve one deterministic frame. Static avatars use [amplitude] 0 and therefore have no
 * breathing, bobbing, gaze, or blinking. Working and needs-user avatars are driven by one shared
 * elapsed clock in the Compose wrapper, so no avatar owns a timer.
 */
fun blobatarMotionFrame(
    seed: String,
    elapsedMs: Long,
    mode: BlobatarMotionMode,
    expression: BlobatarExpression = BlobatarExpression.Idle,
): BlobatarMotionFrame {
    if (mode == BlobatarMotionMode.Static) return BlobatarMotionFrame()
    if (mode == BlobatarMotionMode.OneShot) {
        val progress = (elapsedMs.coerceAtLeast(0L) / 900.0).coerceIn(0.0, 1.0)
        val envelope = kotlin.math.sin(progress * PI)
        return BlobatarMotionFrame(
            breathe = BlobatarPoint(1.0 + envelope * 0.018, 1.0 + envelope * 0.035),
            bob = -envelope * 0.7,
            progress = progress,
        )
    }
    val seeds = blobatarMotionSeeds(seed)
    val pose = expression.pose()
    val amplitude = if (mode == BlobatarMotionMode.Held) 0.42 else 1.0
    return idleAtBlobatar(seeds, elapsedMs.coerceAtLeast(0L).toDouble(), amplitude, pose.shake, pose.rock)
}

/** Alias that reads naturally at call sites resolving a complete frame. */
fun resolveBlobatarMotion(
    seed: String,
    elapsedMs: Long,
    mode: BlobatarMotionMode,
    expression: BlobatarExpression = BlobatarExpression.Idle,
): BlobatarMotionFrame = blobatarMotionFrame(seed, elapsedMs, mode, expression)

/** Evaluate the complete ambient frame from already-resolved seeds. */
fun idleAt(
    seeds: BlobatarMotionSeeds,
    elapsedMs: Long,
    amplitude: Double = 1.0,
    shake: Double = 0.0,
    rock: Double = 0.0,
): BlobatarMotionFrame = idleAtBlobatar(
    seeds = seeds,
    timeMs = elapsedMs.coerceAtLeast(0L).toDouble(),
    amplitude = amplitude.coerceIn(0.0, 1.0),
    shakeAmount = shake,
    rockAmount = rock,
)

private const val BREATHE_MS = 2_800.0
private const val BOB_MS = 3_400.0
private const val ROCK_MS = 900.0
private const val SHAKE_MS = 112.0

private val SACCADE = listOf(
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(0.15, 0.0, 0.0),
    doubleArrayOf(0.165, -0.8, -0.9),
    doubleArrayOf(0.31, -0.8, -0.9),
    doubleArrayOf(0.325, 1.0, 0.1),
    doubleArrayOf(0.47, 1.0, 0.1),
    doubleArrayOf(0.485, -0.15, 0.85),
    doubleArrayOf(0.63, -0.15, 0.85),
    doubleArrayOf(0.645, 0.75, -0.8),
    doubleArrayOf(0.79, 0.75, -0.8),
    doubleArrayOf(0.805, -1.0, -0.15),
    doubleArrayOf(0.985, -1.0, -0.15),
    doubleArrayOf(1.0, 0.0, 0.0),
)

private val WRAP = listOf(
    doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0),
    doubleArrayOf(0.15, 0.0, 0.0, 0.0, 0.0),
    doubleArrayOf(0.165, -0.0176, 0.008, -0.027, 0.648),
    doubleArrayOf(0.31, -0.0176, 0.008, -0.027, 0.648),
    doubleArrayOf(0.325, -0.022, -0.01, -0.003, 0.09),
    doubleArrayOf(0.47, -0.022, -0.01, -0.003, 0.09),
    doubleArrayOf(0.485, -0.0033, 0.0015, -0.0255, -0.115),
    doubleArrayOf(0.63, -0.0033, 0.0015, -0.0255, -0.115),
    doubleArrayOf(0.645, -0.0165, -0.0075, -0.024, -0.54),
    doubleArrayOf(0.79, -0.0165, -0.0075, -0.024, -0.54),
    doubleArrayOf(0.805, -0.022, 0.01, -0.0045, 0.135),
    doubleArrayOf(0.985, -0.022, 0.01, -0.0045, 0.135),
    doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0),
)

private val SHAKE = listOf(
    doubleArrayOf(0.0, 0.62, -0.34),
    doubleArrayOf(0.25, -0.7, 0.22),
    doubleArrayOf(0.5, 0.38, 0.66),
    doubleArrayOf(0.75, -0.44, -0.6),
    doubleArrayOf(1.0, 0.62, -0.34),
)

private fun idleAtBlobatar(
    seeds: BlobatarMotionSeeds,
    timeMs: Double,
    amplitude: Double,
    shakeAmount: Double,
    rockAmount: Double,
): BlobatarMotionFrame {
    val breathe = easeInOut(alternate(timeMs, seeds.phase.toDouble(), BREATHE_MS))
    val bob = easeInOut(alternate(timeMs, seeds.bob.toDouble(), BOB_MS))
    val sac = cycle(timeMs, seeds.saccadePhase.toDouble(), seeds.saccade.toDouble())
    val sh = cycle(timeMs, 0.0, SHAKE_MS)
    val rockCycle = cycle(timeMs, 0.0, ROCK_MS)
    val rockPhase = if (rockCycle < 0.5) {
        1.0 - 2.0 * easeInOut(rockCycle * 2.0)
    } else {
        -1.0 + 2.0 * easeInOut(rockCycle * 2.0 - 1.0)
    }
    val blinkCycle = cycle(timeMs, seeds.blinkPhase.toDouble(), seeds.blink.toDouble())
    val blink = when {
        blinkCycle < 0.972 -> 1.0
        blinkCycle < 0.986 -> 1.0 - 0.92 * amplitude * easeIn(blinkCycle.minus(0.972) / 0.014)
        else -> 1.0 - 0.92 * amplitude * (1.0 - easeOut(blinkCycle.minus(0.986) / 0.014))
    }
    return BlobatarMotionFrame(
        shake = BlobatarPoint(
            stops(sh, SHAKE, 1) * shakeAmount,
            stops(sh, SHAKE, 2) * shakeAmount,
        ),
        breathe = BlobatarPoint(
            1.0 + 0.022 * amplitude * breathe,
            1.0 - 0.018 * amplitude * breathe,
        ),
        bob = -1.1 * amplitude * bob,
        saccade = BlobatarPoint(
            stops(sac, SACCADE, 1) * seeds.lookX * amplitude,
            stops(sac, SACCADE, 2) * seeds.lookY * amplitude,
        ),
        rockPhase = rockPhase,
        blink = blink,
        wrap = BlobatarMotionWrap(
            mx = stops(sac, WRAP, 1) * seeds.lookMX * amplitude,
            side = stops(sac, WRAP, 2) * seeds.lookX * amplitude,
            sy = stops(sac, WRAP, 3) * seeds.lookMY * amplitude,
            rotation = stops(sac, WRAP, 4) * seeds.lookX * seeds.lookY * amplitude,
    ),
    )
}

private fun cycle(time: Double, phase: Double, period: Double): Double {
    val value = (time + phase) / period
    return value - floor(value)
}

private fun alternate(time: Double, phase: Double, period: Double): Double {
    val value = (time + phase) / period
    val whole = floor(value)
    val fraction = value - whole
    return if (whole.toLong() % 2L != 0L) 1.0 - fraction else fraction
}

private fun stops(value: Double, table: List<DoubleArray>, column: Int): Double {
    for (index in table.indices.reversed()) {
        val row = table[index]
        if (value < row[0]) continue
        val next = table.getOrNull(index + 1) ?: return row[column]
        val span = next[0] - row[0]
        return if (span <= 0.0) row[column] else row[column] + (next[column] - row[column]) * ((value - row[0]) / span)
    }
    return table.first()[column]
}

private fun cubicBezier(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
    val cx = 3.0 * x1
    val bx = 3.0 * (x2 - x1) - cx
    val ax = 1.0 - cx - bx
    val cy = 3.0 * y1
    val by = 3.0 * (y2 - y1) - cy
    val ay = 1.0 - cy - by
    var t = x
    repeat(8) {
        val error = ((ax * t + bx) * t + cx) * t - x
        if (abs(error) < 1e-5) return@repeat
        val derivative = (3.0 * ax * t + 2.0 * bx) * t + cx
        if (abs(derivative) < 1e-6) return@repeat
        t -= error / derivative
    }
    return ((ay * t + by) * t + cy) * t
}

private fun easeInOut(value: Double): Double = cubicBezier(0.42, 0.0, 0.58, 1.0, value)
private fun easeIn(value: Double): Double = cubicBezier(0.42, 0.0, 1.0, 1.0, value)
private fun easeOut(value: Double): Double = cubicBezier(0.0, 0.0, 0.58, 1.0, value)
