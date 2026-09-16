package ai.hermes.bots.ui.avatar.blobatar

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.ui.components.BotFaceClock
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * Native, offline Blobatar entry point used by app screens. A valid uploaded image keeps the
 * existing circular crop; a missing or undecodable image falls through to deterministic geometry.
 */
@Composable
fun BlobatarAvatar(
    seed: String,
    size: Dp,
    uploaded: AvatarImage? = null,
    expression: BlobatarExpression = BlobatarExpression.Idle,
    motion: BlobatarMotionMode = BlobatarMotionMode.Static,
    modifier: Modifier = Modifier,
    semanticLabel: String? = null,
    options: BlobatarOptions = BlobatarOptions(),
) {
    val uploadedBitmap = remember(uploaded?.bytes?.contentHashCode()) {
        uploaded?.bytes?.let { bytes ->
            if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val semanticModifier = if (semanticLabel == null) {
        modifier.size(size).clearAndSetSemantics { }
    } else {
        modifier.size(size).semantics { contentDescription = semanticLabel }
    }

    if (uploadedBitmap != null) {
        Image(
            bitmap = uploadedBitmap.asImageBitmap(),
            contentDescription = semanticLabel,
            modifier = semanticModifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        return
    }

    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val animated = motion != BlobatarMotionMode.Static && !reducedMotion
    // BotFaceClock is the existing process-wide frame source. The State is read only in Canvas's
    // draw lambda, so an animation invalidates this face's draw pass rather than its parent UI.
    val tick = if (animated) BotFaceClock.rememberTick() else null
    val resolved = remember(seed, options) { resolveBlobatar(seed, options) }
    Canvas(modifier = semanticModifier) {
        val frame = if (tick == null) {
            BlobatarMotionFrame()
        } else {
            blobatarMotionFrame(seed, tick.value, motion, expression)
        }
        drawBlobatar(resolved, expression, frame)
    }
}
