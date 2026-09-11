package ai.hermes.bots.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The one composer pill (audit A6): 28 dp-radius surface, `Message {name}` placeholder,
 * ONE trailing action — filled send circle that morphs into a stop circle while streaming.
 * IME Send wiring (Phase-4 ANR fix) is preserved; IME Send steers a running turn.
 * The old separate "Steer" button is gone (steering stays on IME Send).
 * Image attach: a leading + queues an image (image.attach_bytes); the pending chip
 * shows above the field until the send clears it.
 */
@Composable
fun ChatComposer(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  streaming: Boolean,
  onSend: () -> Unit,
  onSteer: () -> Unit,
  onInterrupt: () -> Unit,
  modifier: Modifier = Modifier,
  pendingImage: String? = null,
  onAttachImage: (() -> Unit)? = null,
  onRemoveImage: () -> Unit = {},
) {
  val canSubmit = value.isNotBlank() || pendingImage != null
  Row(
    modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Bottom,
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      modifier = Modifier.weight(1f),
    ) {
      Column {
        if (pendingImage != null) {
          Row(
            Modifier
              .fillMaxWidth()
              .padding(start = 14.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              pendingImage,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemoveImage, modifier = Modifier.size(32.dp)) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }
        OutlinedTextField(
          value = value,
          onValueChange = onValueChange,
          modifier = Modifier.fillMaxWidth(),
          placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium)
          },
          leadingIcon = if (onAttachImage != null) {
            {
              IconButton(onClick = onAttachImage) {
                Icon(
                  Icons.Filled.Add,
                  contentDescription = "Attach image",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          } else {
            null
          },
          maxLines = 4,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(
            onSend = {
              if (canSubmit) {
                if (streaming) onSteer() else onSend()
              }
            },
          ),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
          ),
        )
      }
    }
    Spacer(Modifier.width(6.dp))
    Crossfade(targetState = streaming, animationSpec = tween(200), label = "send-stop") { isStreaming ->
      if (isStreaming) {
        IconButton(onClick = onInterrupt, modifier = Modifier.size(48.dp)) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(40.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Box(
                Modifier
                  .size(12.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(MaterialTheme.colorScheme.error),
              )
            }
          }
        }
      } else {
        IconButton(
          onClick = { if (canSubmit) onSend() },
          modifier = Modifier.size(48.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp).alpha(if (canSubmit) 1f else 0.4f),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }
        }
      }
    }
  }
}
