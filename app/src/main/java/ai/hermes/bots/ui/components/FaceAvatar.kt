package ai.hermes.bots.ui.components

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.ui.avatar.blobatar.BlobatarAvatar
import ai.hermes.bots.ui.avatar.blobatar.BlobatarExpression
import ai.hermes.bots.ui.avatar.blobatar.BlobatarMotionMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp

/** App-facing state mapping for the native Blobatar renderer. */
enum class FaceState {
    Idle,
    Working,
    NeedsUser,
    Error,
    Offline,
}

/**
 * Shared avatar entry point. Uploaded profile images retain their circular crop; generated faces
 * use the canonical seed and the native generation-2 Blobatar renderer.
 */
@Composable
fun FaceAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    real: AvatarImage? = null,
    state: FaceState = FaceState.Idle,
    a11yLabel: String? = null,
    /** Pass the stable profile name when [name] is a display label. */
    seed: String = name,
) {
    val (expression, motion) = when (state) {
        FaceState.Idle -> BlobatarExpression.Idle to BlobatarMotionMode.Static
        FaceState.Working -> BlobatarExpression.Thinking to BlobatarMotionMode.Ambient
        FaceState.NeedsUser -> BlobatarExpression.Unsure to BlobatarMotionMode.Held
        FaceState.Error -> BlobatarExpression.Sick to BlobatarMotionMode.Static
        FaceState.Offline -> BlobatarExpression.Idle to BlobatarMotionMode.Static
    }
    BlobatarAvatar(
        seed = seed,
        size = size,
        uploaded = real,
        expression = expression,
        motion = motion,
        modifier = modifier.alpha(if (state == FaceState.Offline) 0.56f else 1f),
        semanticLabel = a11yLabel,
    )
}
