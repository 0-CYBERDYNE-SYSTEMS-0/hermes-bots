package ai.hermes.bots.ui.components

import ai.hermes.bots.data.AvatarImage
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
) {
    if (real != null) {
        val bitmap = remember(real.bytes) {
            BitmapFactory.decodeByteArray(real.bytes, 0, real.bytes.size)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name,
                modifier = modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    val spec = remember(name) { FaceHash.spec(name) }
    Canvas(modifier = modifier.size(size)) {
        drawFace(spec)
    }
}

private fun DrawScope.drawFace(spec: FaceSpec) {
    val body = hslColor(spec.hue, 0.52f, 0.60f)
    val ink = if (body.luminance() < 0.35f) Color(0xFFF2F5F7) else Color(0xFF14181C)
    val s = this.size.minDimension
    val cx = this.size.width / 2f
    val cy = this.size.height / 2f
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
    // two eyes
    val gap = s * spec.eyeGap
    val eyeY = s * spec.eyeY
    drawCircle(ink, radius = s * spec.eyeR, center = Offset(cx - gap * s, cy - s / 2f + eyeY * s))
    drawCircle(ink, radius = s * spec.eyeR, center = Offset(cx + gap * s, cy - s / 2f + eyeY * s))
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
