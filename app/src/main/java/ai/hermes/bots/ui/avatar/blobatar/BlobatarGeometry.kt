package ai.hermes.bots.ui.avatar.blobatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class BlobatarShape { Round, Organic, Boxy, Capsule, Nub, Cloud, Droplet, Hexagon, Sun, Triangle }

enum class BlobatarBackdrop { None, Squircle, Circle, Square }

data class BlobatarPoint(val x: Double, val y: Double)
data class BlobatarEllipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double)
data class BlobatarPetal(val cx: Double, val cy: Double, val radius: Double)

data class BlobatarEye(
    val cx: Double,
    val cy: Double,
    val rx: Double,
    val ry: Double,
    val n: Double,
    val rotation: Double,
)

data class BlobatarBody(
    var cx: Double,
    var cy: Double,
    var rx: Double,
    var ry: Double,
    var n: Double,
    var rotation: Double,
    val radii: List<Double>,
    var sides: Int? = null,
    var rounding: Double? = null,
)

sealed interface BlobatarPathCommand {
    data class Move(val point: BlobatarPoint) : BlobatarPathCommand
    data class Line(val point: BlobatarPoint) : BlobatarPathCommand
    data class Quadratic(val control: BlobatarPoint, val end: BlobatarPoint) : BlobatarPathCommand
    data class Cubic(
        val firstControl: BlobatarPoint,
        val secondControl: BlobatarPoint,
        val end: BlobatarPoint,
    ) : BlobatarPathCommand
    data object Close : BlobatarPathCommand
}

data class BlobatarPath(val commands: List<BlobatarPathCommand>)

data class BlobatarLayout(
    val shape: BlobatarShape,
    val body: BlobatarBody,
    val face: BlobatarEllipse,
    val petals: List<BlobatarPetal>,
    val extras: List<BlobatarPath>,
    val eyes: List<BlobatarEye>,
    val bodyPath: BlobatarPath,
)

data class BlobatarOptions(
    val normalize: Boolean = true,
    val traitOverrides: Map<String, BlobatarTraitOverride> = emptyMap(),
    val hue: Double? = null,
    val tone: Double? = null,
    val palette: BlobatarPaletteOverride = BlobatarPaletteOverride(),
    val enforceContrast: Boolean = true,
    val backdrop: BlobatarBackdrop = BlobatarBackdrop.None,
)

data class ResolvedBlobatar(
    val layout: BlobatarLayout,
    val palette: BlobatarPalette,
    val backdrop: BlobatarBackdrop,
)

fun resolveBlobatar(seed: String, options: BlobatarOptions = BlobatarOptions()): ResolvedBlobatar {
    val traits = BlobatarTraits(seed, options.normalize, options.traitOverrides)
    val base = blobatarPalette(
        hue = options.hue ?: traits.number("hue", 0.0, 360.0),
        enforceContrast = options.enforceContrast,
        tone = options.tone ?: traits("tone"),
    )
    return ResolvedBlobatar(
        layout = computeBlobatarLayout(traits),
        palette = BlobatarPalette(
            background = options.palette.background ?: base.background,
            head = options.palette.head ?: base.head,
            eye = options.palette.eye ?: base.eye,
        ),
        backdrop = options.backdrop,
    )
}

fun computeBlobatarLayout(traits: BlobatarTraits): BlobatarLayout {
    val shape = pickShape(traits("shape"))
    val core = when (shape) {
        BlobatarShape.Round -> 1.0
        BlobatarShape.Organic -> 0.98
        BlobatarShape.Boxy -> 0.86
        BlobatarShape.Capsule -> 1.02
        BlobatarShape.Nub -> 0.88
        BlobatarShape.Cloud -> 0.78
        BlobatarShape.Droplet -> 0.78
        BlobatarShape.Hexagon -> 1.05
        BlobatarShape.Sun -> 0.7
        BlobatarShape.Triangle -> 1.15
    }
    val radius = traits.number("body.r", 31.0, 38.0) * core
    val body = BlobatarBody(
        cx = 50.0 + traits.jitter("body.x", 1.5),
        cy = 50.0 + traits.jitter("body.y", 1.5),
        rx = radius,
        ry = radius * traits.number("body.ratio", 0.92, 1.08),
        n = traits.number("body.n", 1.9, 2.5),
        rotation = 0.0,
        radii = List(traits.integer("body.pts", 6, 8)) { index ->
            1.0 + traits.jitter("body.r$index", 0.16)
        },
    )
    patchBody(shape, traits, body)
    val face = faceFor(shape, body)
    val petals = mutableListOf<BlobatarPetal>()
    val extras = mutableListOf<BlobatarPath>()
    decorate(shape, traits, body, petals, extras)
    return BlobatarLayout(
        shape = shape,
        body = body,
        face = face,
        petals = petals,
        extras = extras,
        eyes = fitEyes(traits, body, face),
        bodyPath = bodyPath(shape, body),
    )
}

fun blobatarBackdropPath(backdrop: BlobatarBackdrop): BlobatarPath? = when (backdrop) {
    BlobatarBackdrop.None -> null
    BlobatarBackdrop.Square -> BlobatarPath(
        listOf(
            BlobatarPathCommand.Move(BlobatarPoint(0.0, 0.0)),
            BlobatarPathCommand.Line(BlobatarPoint(100.0, 0.0)),
            BlobatarPathCommand.Line(BlobatarPoint(100.0, 100.0)),
            BlobatarPathCommand.Line(BlobatarPoint(0.0, 100.0)),
            BlobatarPathCommand.Close,
        ),
    )
    BlobatarBackdrop.Circle -> superellipsePath(50.0, 50.0, 50.0, 50.0, 2.0, 0.0)
    BlobatarBackdrop.Squircle -> superellipsePath(50.0, 50.0, 50.0, 50.0, 6.0, 0.0)
}

private fun pickShape(value: Double): BlobatarShape = when {
    value < 0.22 -> BlobatarShape.Round
    value < 0.48 -> BlobatarShape.Organic
    value < 0.6 -> BlobatarShape.Boxy
    value < 0.7 -> BlobatarShape.Capsule
    value < 0.79 -> BlobatarShape.Nub
    value < 0.86 -> BlobatarShape.Cloud
    value < 0.915 -> BlobatarShape.Droplet
    value < 0.95 -> BlobatarShape.Hexagon
    value < 0.98 -> BlobatarShape.Sun
    else -> BlobatarShape.Triangle
}

private fun patchBody(shape: BlobatarShape, traits: BlobatarTraits, body: BlobatarBody) {
    when (shape) {
        BlobatarShape.Boxy -> {
            body.n = traits.number("body.n", 3.4, 6.0)
            body.rotation = traits.number("body.rot", -20.0, 20.0)
        }
        BlobatarShape.Capsule -> body.ry *= traits.number("capsule.squat", 0.55, 0.68)
        BlobatarShape.Droplet -> {
            body.cy += 0.22 * body.ry
            body.n = 2.0
        }
        BlobatarShape.Hexagon -> {
            body.sides = 6
            body.rotation = traits.number("body.rot", -12.0, 12.0)
            body.rounding = traits.number("poly.round", 0.24, 0.5)
        }
        BlobatarShape.Triangle -> {
            body.sides = 3
            body.rotation = traits.number("body.rot", -5.0, 5.0)
            body.rounding = traits.number("poly.round", 0.24, 0.5)
        }
        else -> Unit
    }
}

private fun faceFor(shape: BlobatarShape, body: BlobatarBody): BlobatarEllipse = when (shape) {
    BlobatarShape.Organic, BlobatarShape.Cloud -> {
        val scale = body.radii.min() * 0.95
        BlobatarEllipse(body.cx, body.cy, body.rx * scale, body.ry * scale)
    }
    BlobatarShape.Capsule -> BlobatarEllipse(body.cx, body.cy, body.rx * 0.94, body.ry * 0.94)
    BlobatarShape.Droplet -> BlobatarEllipse(
        body.cx,
        body.cy + body.ry * 0.05,
        body.rx * 0.88,
        body.ry * 0.88,
    )
    BlobatarShape.Hexagon -> BlobatarEllipse(body.cx, body.cy, body.rx * 0.84, body.ry * 0.84)
    BlobatarShape.Triangle -> BlobatarEllipse(
        body.cx,
        body.cy + body.ry * 0.1,
        body.rx * 0.54,
        body.ry * 0.36,
    )
    else -> BlobatarEllipse(body.cx, body.cy, body.rx, body.ry)
}

private fun decorate(
    shape: BlobatarShape,
    traits: BlobatarTraits,
    body: BlobatarBody,
    petals: MutableList<BlobatarPetal>,
    extras: MutableList<BlobatarPath>,
) {
    when (shape) {
        BlobatarShape.Capsule -> for (side in listOf(-1.0, 1.0)) {
            petals += BlobatarPetal(body.cx + side * (body.rx - body.ry), body.cy, body.ry)
        }
        BlobatarShape.Nub -> repeat(traits.integer("nub.n", 1, 2)) { index ->
            val angle = traits.number("nub.a$index", 0.0, 2.0 * PI)
            petals += BlobatarPetal(
                body.cx + cos(angle) * body.rx * 0.88,
                body.cy + sin(angle) * body.rx * 0.88,
                body.rx * traits.number("nub.r$index", 0.24, 0.4),
            )
        }
        BlobatarShape.Cloud -> {
            val count = traits.integer("cloud.n", 4, 6)
            repeat(count) { index ->
                val angle = PI + (PI * (index + 0.5)) / count
                petals += BlobatarPetal(
                    body.cx + cos(angle) * body.rx * 0.8,
                    body.cy + sin(angle) * body.rx * 0.5,
                    body.rx * traits.number("cloud.r$index", 0.44, 0.62),
                )
            }
        }
        BlobatarShape.Droplet -> extras += taperPath(
            body.cx,
            body.cy,
            body.rx,
            body.ry,
            traits.number("droplet.tip", 1.4, 1.65),
        )
        BlobatarShape.Sun -> {
            val count = traits.integer("sun.n", 6, 9)
            val distance = body.rx * traits.number("sun.dist", 1.0, 1.08)
            val petalRadius = body.rx * traits.number("sun.r", 0.2, 0.26)
            val offset = traits.number("sun.rot", 0.0, 2.0 * PI)
            repeat(count) { index ->
                val angle = offset + (2.0 * PI * index) / count
                petals += BlobatarPetal(
                    body.cx + cos(angle) * distance,
                    body.cy + sin(angle) * distance,
                    petalRadius,
                )
            }
        }
        else -> Unit
    }
}

private fun fitEyes(traits: BlobatarTraits, body: BlobatarBody, face: BlobatarEllipse): List<BlobatarEye> {
    val initialRx = traits.number("eye.rx", 0.075, 0.105) * body.rx
    val ratio = traits.number("eye.ratio", 1.9, 3.2)
    val scale = traits.number("eye.scale", 0.78, 1.24)
    val stretch = traits.number("eye.stretch", 0.85, 1.18)
    val clearance = traits.number("eye.gap", 0.1, 0.24) * body.rx
    val wide = initialRx * max(1.0, scale)
    val tall = initialRx * ratio * max(1.0, scale * stretch)
    val initialGap = wide + body.rx * 0.03 + clearance
    val gazeX = traits.jitter("gaze.x", 0.09) * face.rx
    val gazeY = traits.number("gaze.y", -0.2, 0.08) * face.ry
    val eyeDy = traits.jitter("eye.dy", 0.04) * face.ry
    val reach = hypotV8(wide, tall)
    val need = hypotV8(
        (abs(gazeX) + initialGap + reach) / face.rx,
        (abs(gazeY) + abs(eyeDy) + reach) / face.ry,
    )
    val fit = if (need > 0.9) 0.9 / need else 1.0
    val eyeRx = initialRx * fit
    val eyeRy = eyeRx * ratio
    val gap = initialGap * fit
    val room = max(0.0, min(1.0, clearance / tall))
    val bound = min(12.0, asin(room) * 180.0 / PI)
    val lean = traits.number("eye.lean", -1.0, 1.0) * bound
    val lean2 = (lean + traits.jitter("eye.lean2", 3.5)).coerceIn(-12.0, 12.0)
    val cx = face.cx + gazeX * fit
    val cy = face.cy + gazeY * fit
    val exponent = traits.number("eye.n", 3.5, 6.0)
    return listOf(
        BlobatarEye(cx - gap, cy, eyeRx, eyeRy, exponent, lean),
        BlobatarEye(cx + gap, cy + eyeDy * fit, eyeRx * scale, eyeRy * scale * stretch, exponent, lean2),
    )
}

private fun hypotV8(first: Double, second: Double): Double {
    val maximum = max(abs(first), abs(second))
    if (maximum == 0.0) return 0.0
    val x = abs(first) / maximum
    val y = abs(second) / maximum
    return sqrt(x * x + y * y) * maximum
}

private fun bodyPath(shape: BlobatarShape, body: BlobatarBody): BlobatarPath = when (shape) {
    BlobatarShape.Organic, BlobatarShape.Cloud -> organicPath(body)
    BlobatarShape.Capsule -> boxPath(body.cx, body.cy, body.rx - body.ry, body.ry)
    BlobatarShape.Hexagon, BlobatarShape.Triangle -> polygonPath(body)
    else -> superellipsePath(body.cx, body.cy, body.rx, body.ry, body.n, body.rotation)
}

internal fun superellipsePath(
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    n: Double,
    rotation: Double,
): BlobatarPath {
    val k = min(1.0, (8.0 * 2.0.pow(-1.0 / n) - 4.0) / 3.0)
    val points = listOf(
        BlobatarPoint(rx, 0.0), BlobatarPoint(rx, ry * k), BlobatarPoint(rx * k, ry),
        BlobatarPoint(0.0, ry), BlobatarPoint(-rx * k, ry), BlobatarPoint(-rx, ry * k),
        BlobatarPoint(-rx, 0.0), BlobatarPoint(-rx, -ry * k), BlobatarPoint(-rx * k, -ry),
        BlobatarPoint(0.0, -ry), BlobatarPoint(rx * k, -ry), BlobatarPoint(rx, -ry * k),
        BlobatarPoint(rx, 0.0),
    )
    val radians = rotation * PI / 180.0
    fun at(index: Int): BlobatarPoint {
        val point = points[index]
        return BlobatarPoint(
            cx + point.x * cos(radians) - point.y * sin(radians),
            cy + point.x * sin(radians) + point.y * cos(radians),
        )
    }
    val commands = mutableListOf<BlobatarPathCommand>(BlobatarPathCommand.Move(at(0)))
    for (index in 1 until 13 step 3) {
        commands += BlobatarPathCommand.Cubic(at(index), at(index + 1), at(index + 2))
    }
    commands += BlobatarPathCommand.Close
    return BlobatarPath(commands)
}

private fun organicPath(body: BlobatarBody): BlobatarPath {
    val count = body.radii.size
    val rotation = body.rotation * PI / 180.0
    val points = List(count) { index ->
        val angle = rotation + 2.0 * PI * index / count
        BlobatarPoint(
            body.cx + body.rx * body.radii[index] * cos(angle),
            body.cy + body.ry * body.radii[index] * sin(angle),
        )
    }
    fun at(index: Int): BlobatarPoint = points[((index % count) + count) % count]
    val commands = mutableListOf<BlobatarPathCommand>(BlobatarPathCommand.Move(at(0)))
    repeat(count) { index ->
        val p0 = at(index - 1)
        val p1 = at(index)
        val p2 = at(index + 1)
        val p3 = at(index + 2)
        commands += BlobatarPathCommand.Cubic(
            BlobatarPoint(p1.x + (p2.x - p0.x) / 6.0, p1.y + (p2.y - p0.y) / 6.0),
            BlobatarPoint(p2.x - (p3.x - p1.x) / 6.0, p2.y - (p3.y - p1.y) / 6.0),
            p2,
        )
    }
    commands += BlobatarPathCommand.Close
    return BlobatarPath(commands)
}

private fun boxPath(cx: Double, cy: Double, rx: Double, ry: Double): BlobatarPath = BlobatarPath(
    listOf(
        BlobatarPathCommand.Move(BlobatarPoint(cx - rx, cy - ry)),
        BlobatarPathCommand.Line(BlobatarPoint(cx + rx, cy - ry)),
        BlobatarPathCommand.Line(BlobatarPoint(cx + rx, cy + ry)),
        BlobatarPathCommand.Line(BlobatarPoint(cx - rx, cy + ry)),
        BlobatarPathCommand.Close,
    ),
)

private fun polygonPath(body: BlobatarBody): BlobatarPath {
    val sides = requireNotNull(body.sides)
    val rounding = body.rounding ?: 0.3
    val k = if (rounding > 0.0) min(rounding, 1.0) / 2.0 else 0.0
    val startAngle = body.rotation * PI / 180.0 - PI / 2.0
    val vertices = List(sides) { index ->
        val angle = startAngle + 2.0 * PI * index / sides
        BlobatarPoint(body.cx + body.rx * cos(angle), body.cy + body.ry * sin(angle))
    }
    fun at(index: Int): BlobatarPoint = vertices[((index % sides) + sides) % sides]
    fun cut(index: Int, neighbor: Int): BlobatarPoint {
        val first = at(index)
        val second = at(neighbor)
        return BlobatarPoint(first.x + (second.x - first.x) * k, first.y + (second.y - first.y) * k)
    }
    val commands = mutableListOf<BlobatarPathCommand>(BlobatarPathCommand.Move(cut(0, -1)))
    repeat(sides) { index ->
        commands += BlobatarPathCommand.Quadratic(at(index), cut(index, index + 1))
        if (k < 0.5) commands += BlobatarPathCommand.Line(cut(index + 1, index))
    }
    commands += BlobatarPathCommand.Close
    return BlobatarPath(commands)
}

private fun taperPath(cx: Double, cy: Double, rx: Double, ry: Double, tip: Double): BlobatarPath {
    val t = max(1.05, tip)
    val tangentX = rx * sqrt(1.0 - 1.0 / (t * t))
    val tangentY = cy - ry / t
    val apex = cy - t * ry
    val easedX = tangentX * 0.14
    val easedY = tangentY + 0.86 * (apex - tangentY)
    return BlobatarPath(
        listOf(
            BlobatarPathCommand.Move(BlobatarPoint(cx - tangentX, tangentY)),
            BlobatarPathCommand.Line(BlobatarPoint(cx - easedX, easedY)),
            BlobatarPathCommand.Quadratic(BlobatarPoint(cx, apex), BlobatarPoint(cx + easedX, easedY)),
            BlobatarPathCommand.Line(BlobatarPoint(cx + tangentX, tangentY)),
            BlobatarPathCommand.Close,
        ),
    )
}
