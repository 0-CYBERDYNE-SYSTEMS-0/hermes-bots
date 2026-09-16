package ai.hermes.bots.ui.avatar.blobatar

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class BlobatarOklch(val lightness: Double, val chroma: Double, val hue: Double)

data class BlobatarPalette(
    val background: String,
    val head: String,
    val eye: String,
)

data class BlobatarPaletteOverride(
    val background: String? = null,
    val head: String? = null,
    val eye: String? = null,
)

/** Resolve the upstream expression tint while preserving the palette's contrast contract. */
fun blobatarExpressionPalette(
    palette: BlobatarPalette,
    expression: BlobatarExpression,
): BlobatarPalette {
    val pose = expression.pose()
    if (pose.heat <= 0.0) return palette
    val tint = when (expression) {
        BlobatarExpression.Mad -> BlobatarTint(27.0, 0.58, 0.6, 0.18)
        BlobatarExpression.Love -> BlobatarTint(358.0, 0.72, 0.55, 0.16)
        BlobatarExpression.Shy -> BlobatarTint(12.0, 0.84, 0.4, 0.1)
        BlobatarExpression.Sick -> BlobatarTint(142.0, 0.66, 0.6, 0.13)
        else -> return palette
    }
    val base = hexToBlobatarOklch(palette.head)
    val baseEye = hexToBlobatarOklch(palette.eye)
    var tintedHead = ensureBlobatarContrast(
        BlobatarOklch(
            lightness = base.lightness + (tint.lightness - base.lightness) * tint.pull,
            chroma = max(base.chroma, tint.chroma),
            hue = tint.hue,
        ),
        darkSurface,
        1.5,
    )
    var tintedEye = ensureBlobatarContrast(baseEye, tintedHead, 4.55)
    repeat(40) {
        val headHex = blobatarHex(tintedHead)
        val eyeHex = blobatarHex(tintedEye)
        val worst = (0..10).minOf { index ->
            val amount = index / 10.0
            blobatarContrast(
                hexToBlobatarOklch(mixBlobatarHex(palette.eye, eyeHex, amount)),
                hexToBlobatarOklch(mixBlobatarHex(palette.head, headHex, amount)),
            )
        }
        if (worst >= 4.55) return BlobatarPalette(
            background = palette.background,
            head = mixBlobatarHex(palette.head, headHex, pose.heat),
            eye = mixBlobatarHex(palette.eye, eyeHex, pose.heat),
        )
        val direction = if (tintedEye.lightness >= tintedHead.lightness) 1.0 else -1.0
        val next = (tintedEye.lightness + direction * 0.02).coerceIn(0.0, 1.0)
        if (next == tintedEye.lightness) return@repeat
        tintedEye = tintedEye.copy(lightness = next)
    }
    val headHex = blobatarHex(tintedHead)
    val eyeHex = blobatarHex(tintedEye)
    return BlobatarPalette(
        background = palette.background,
        head = mixBlobatarHex(palette.head, headHex, pose.heat),
        eye = mixBlobatarHex(palette.eye, eyeHex, pose.heat),
    )
}

private data class Tone(val lightness: Double, val chroma: Double)

private data class BlobatarTint(
    val hue: Double,
    val lightness: Double,
    val pull: Double,
    val chroma: Double,
)

private val tones = listOf(
    0.2 to Tone(0.86, 0.085),
    0.36 to Tone(0.9, 0.028),
    0.62 to Tone(0.73, 0.135),
    0.8 to Tone(0.62, 0.165),
    0.93 to Tone(0.87, 0.16),
    1.0 to Tone(0.34, 0.035),
)

private val darkSurface = BlobatarOklch(0.145, 0.0, 0.0)

fun blobatarPalette(hue: Double, enforceContrast: Boolean = true, tone: Double = 0.0): BlobatarPalette {
    val swatch = tones.firstOrNull { tone < it.first }?.second ?: tones.first().second
    var head = ensureBlobatarContrast(
        BlobatarOklch(swatch.lightness, swatch.chroma, hue),
        darkSurface,
        1.5,
    )
    var eye = if (head.lightness >= 0.5) {
        BlobatarOklch(0.17, 0.02, hue)
    } else {
        BlobatarOklch(0.97, 0.012, hue)
    }
    val background = BlobatarOklch(0.965, 0.01, hue)
    if (enforceContrast) {
        head = ensureBlobatarContrast(head, background, 1.25)
        eye = ensureBlobatarContrast(eye, head, 4.5)
    }
    return BlobatarPalette(
        background = blobatarHex(background),
        head = blobatarHex(head),
        eye = blobatarHex(eye),
    )
}

fun blobatarContrast(first: BlobatarOklch, second: BlobatarOklch): Double {
    val a = luminance(first)
    val b = luminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

fun ensureBlobatarContrast(
    foreground: BlobatarOklch,
    background: BlobatarOklch,
    minimum: Double,
): BlobatarOklch {
    if (blobatarContrast(foreground, background) >= minimum) return foreground
    val lean = if (foreground.lightness >= background.lightness) 1.0 else -1.0
    for (direction in listOf(lean, -lean)) {
        var probe = foreground
        repeat(60) {
            probe = probe.copy(lightness = (probe.lightness + direction * 0.02).coerceIn(0.0, 1.0))
            if (blobatarContrast(probe, background) >= minimum) return probe
            if (probe.lightness == 0.0 || probe.lightness == 1.0) return@repeat
        }
    }
    val black = foreground.copy(lightness = 0.0, chroma = 0.0)
    val white = foreground.copy(lightness = 1.0, chroma = 0.0)
    return if (blobatarContrast(black, background) >= blobatarContrast(white, background)) black else white
}

fun blobatarHex(color: BlobatarOklch): String = buildString(7) {
    append('#')
    resolve(color).forEach { linear ->
        val srgb = if (linear <= 0.0031308) {
            12.92 * linear
        } else {
            1.055 * linear.pow(1.0 / 2.4) - 0.055
        }
        append((srgb * 255.0).roundToInt().toString(16).padStart(2, '0'))
    }
}

private fun luminance(color: BlobatarOklch): Double {
    val (red, green, blue) = resolve(color)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

private fun resolve(color: BlobatarOklch): List<Double> {
    var rgb = linear(color)
    if (rgb.any { it < -1e-4 || it > 1.0 + 1e-4 }) {
        var low = 0.0
        var high = color.chroma
        repeat(12) {
            val middle = (low + high) / 2.0
            val candidate = linear(color.copy(chroma = middle))
            if (candidate.all { it >= -1e-4 && it <= 1.0 + 1e-4 }) low = middle else high = middle
        }
        rgb = linear(color.copy(chroma = low))
    }
    return rgb.map { it.coerceIn(0.0, 1.0) }
}

private fun linear(color: BlobatarOklch): List<Double> {
    val radians = color.hue * PI / 180.0
    val a = color.chroma * cos(radians)
    val b = color.chroma * sin(radians)
    val l0 = color.lightness + 0.3963377774 * a + 0.2158037573 * b
    val m0 = color.lightness - 0.1055613458 * a - 0.0638541728 * b
    val s0 = color.lightness - 0.0894841775 * a - 1.291485548 * b
    val l = l0 * l0 * l0
    val m = m0 * m0 * m0
    val s = s0 * s0 * s0
    return listOf(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
    )
}

private fun mixBlobatarHex(first: String, second: String, amount: Double): String {
    val a = hexToBlobatarOklch(first)
    val b = hexToBlobatarOklch(second)
    val aRadians = a.hue * PI / 180.0
    val bRadians = b.hue * PI / 180.0
    val ax = a.chroma * cos(aRadians)
    val ay = a.chroma * sin(aRadians)
    val bx = b.chroma * cos(bRadians)
    val by = b.chroma * sin(bRadians)
    val x = ax + (bx - ax) * amount
    val y = ay + (by - ay) * amount
    return blobatarHex(
        BlobatarOklch(
            lightness = a.lightness + (b.lightness - a.lightness) * amount,
            chroma = sqrt(x * x + y * y),
            hue = atan2(y, x) * 180.0 / PI,
        ),
    )
}

internal fun hexToBlobatarOklch(hex: String): BlobatarOklch {
    val value = hex.removePrefix("#").toInt(16)
    fun channel(byte: Int): Double {
        val component = byte / 255.0
        return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
    }
    val red = channel((value shr 16) and 0xff)
    val green = channel((value shr 8) and 0xff)
    val blue = channel(value and 0xff)
    val l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
    val m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
    val s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
    val a = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
    val b = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
    return BlobatarOklch(
        lightness = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
        chroma = sqrt(a * a + b * b),
        hue = atan2(b, a) * 180.0 / PI,
    )
}
