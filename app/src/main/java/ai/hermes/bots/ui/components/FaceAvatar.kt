package ai.hermes.bots.ui.components

import ai.hermes.bots.data.AvatarImage
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pose of a drawn face: [Idle] blinks on the lazy window; [Working] adds the working pose (§3.2). */
enum class FaceState { Idle, Working }

/**
 * Deterministic "blobatar" face (desktop avatar.tsx parity): name → hashed shape,
 * hue, and eye placement; eyes flip ink on dark bodies. Real uploaded avatars
 * (profiles.get_asset) override the generated face.
 */
data class FaceSpec(val shape: Int, val hue: Float, val eyeGap: Float, val eyeY: Float, val eyeR: Float)

object FaceHash {
    fun hashOf(name: String): Int {
        var h = 0
        for (ch in name) h = h * 31 + ch.code
        return h
    }

    fun spec(name: String): FaceSpec {
        val h = hashOf(name)
        val h2 = h * 7919 + 17
        return FaceSpec(
            shape = abs(h) % 6,
            hue = abs(h2 % 360).toFloat(),
            eyeGap = 0.16f + abs(h2 / 7 % 100) / 100f * 0.12f,   // 0.16..0.28 (half-distance)
            eyeY = 0.42f + abs(h2 / 13 % 100) / 100f * 0.14f,    // 0.42..0.56
            eyeR = 0.055f + abs(h2 / 29 % 100) / 100f * 0.035f,  // 0.055..0.09
        )
    }
}

@Composable
fun FaceAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    real: AvatarImage? = null,
    state: FaceState = FaceState.Idle,
    a11yLabel: String? = null,
) {
    if (real != null) {
        val bitmap = remember(real.bytes) {
            BitmapFactory.decodeByteArray(real.bytes, 0, real.bytes.size)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = a11yLabel ?: name,
                modifier = modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    val spec = remember(name) { FaceHash.spec(name) }
    // Reduced motion (Settings.Global.ANIMATOR_DURATION_SCALE = 0): static idle face — no
    // tick collection, no pose (§3.2). Read once per composition instance.
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    // Idle faces blink too, so Idle collects the shared clock as well (unless reduced motion).
    val tick = if (reducedMotion) null else BotFaceClock.rememberTick()
    val working = !reducedMotion && state == FaceState.Working
    val tMs = tick?.value ?: 0L
    // Face animation is decorative (§3.2): default faces are stripped from the a11y tree;
    // callers that want a label pass a11yLabel ("{name}, {state}") instead.
    val faceModifier = if (a11yLabel == null) {
        modifier.size(size).clearAndSetSemantics { }
    } else {
        modifier.size(size).semantics { contentDescription = a11yLabel }
    }
    Canvas(modifier = faceModifier) {
        drawFace(spec, tMs, working)
    }
}

private fun DrawScope.drawFace(spec: FaceSpec, tMs: Long, working: Boolean) {
    val body = hslColor(spec.hue, 0.52f, 0.60f)
    val ink = if (body.luminance() < 0.35f) Color(0xFFF2F5F7) else Color(0xFF14181C)
    val s = this.size.minDimension
    val cx = this.size.width / 2f
    val cy = this.size.height / 2f
    // Working pose (§3.2): subtle body squash + eyes drift up a touch; idle has neither.
    val squashY = if (working) 0.96f else 1f
    val eyeLift = if (working) s * 0.035f else 0f
    val blinking = BotFaceClock.isBlinking(tMs, working)
    scale(1f, squashY, pivot = Offset(cx, cy)) {
        val bodyPath = Path()
        when (spec.shape) {
            0 -> bodyPath.addOval(Rect(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f))
            1 -> bodyPath.addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f, CornerRadius(s * 0.22f),
                ),
            )
            2 -> { // hexagon
                for (i in 0 until 6) {
                    val angle = Math.PI / 3.0 * i - Math.PI / 6.0
                    val px = cx + (s * 0.48f * cos(angle)).toFloat()
                    val py = cy + (s * 0.48f * sin(angle)).toFloat()
                    if (i == 0) bodyPath.moveTo(px, py) else bodyPath.lineTo(px, py)
                }
                bodyPath.close()
            }
            3 -> { // triangle
                bodyPath.moveTo(cx, cy - s * 0.48f)
                bodyPath.lineTo(cx + s * 0.46f, cy + s * 0.40f)
                bodyPath.lineTo(cx - s * 0.46f, cy + s * 0.40f)
                bodyPath.close()
            }
            4 -> { // diamond
                bodyPath.moveTo(cx, cy - s * 0.48f)
                bodyPath.lineTo(cx + s * 0.44f, cy)
                bodyPath.lineTo(cx, cy + s * 0.48f)
                bodyPath.lineTo(cx - s * 0.44f, cy)
                bodyPath.close()
            }
            else -> { // drop/egg blob
                bodyPath.addOval(
                    Rect(cx - s * 0.44f, cy - s * 0.48f, cx + s * 0.44f, cy + s * 0.48f),
                )
            }
        }
        drawPath(bodyPath, body)
        // two eyes; a blink squashes eye height to near zero. Positions are fractions of the
        // size (desktop avatar.tsx parity). NOTE: this fixes the former double-`s` scaling
        // (`gap * s` / `eyeY * s`) that drew generated eyes far off-canvas — without it the
        // §3.2 blink/working pose would be invisible on every generated face.
        // Triangle bodies taper sharply toward the apex, so the raw hash gap (up to 0.28 s
        // + eye radius) can park an eye off the body — clamp it to the half-width at the
        // eye row (with margin for the working pose's upward eye lift).
        val gap = s * when (spec.shape) {
            3 -> {
                val eyeRow = (spec.eyeY - 0.035f).coerceAtLeast(0.07f) // 0.035 = working eyeLift
                val halfWidthAtEye = 0.46f * eyeRow / 0.88f
                maxOf(
                    spec.eyeR + 0.02f,
                    minOf(spec.eyeGap, halfWidthAtEye - spec.eyeR - 0.03f),
                )
            }
            else -> spec.eyeGap
        }
        val eyeY = cy - s / 2f + s * spec.eyeY - eyeLift
        val eyeR = s * spec.eyeR
        val eyeH = eyeR * (if (blinking) 0.12f else 1f)
        drawOval(ink, topLeft = Offset(cx - gap - eyeR, eyeY - eyeH), size = Size(eyeR * 2f, eyeH * 2f))
        drawOval(ink, topLeft = Offset(cx + gap - eyeR, eyeY - eyeH), size = Size(eyeR * 2f, eyeH * 2f))
    }
}

// Fallback for androidx.compose.ui.graphics.hsl (unresolved in this build):
// standard HSL → RGB conversion, channels as 0..1 floats.
private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val h = (((hue % 360f) + 360f) % 360f) / 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    if (s == 0f) return Color(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
}
