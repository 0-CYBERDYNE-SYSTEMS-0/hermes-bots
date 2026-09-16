package ai.hermes.bots.ui.avatar.blobatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

/**
 * Compose renderer for the framework-free Blobatar layout. Geometry remains in the core model;
 * this file only translates the 100 × 100 paths to the current canvas and applies a frame.
 */
fun DrawScope.drawBlobatar(
    resolved: ResolvedBlobatar,
    expression: BlobatarExpression = BlobatarExpression.Idle,
    frame: BlobatarMotionFrame = BlobatarMotionFrame(),
) {
    val pose = expression.pose()
    val palette = blobatarExpressionPalette(resolved.palette, expression)
    val side = min(size.width, size.height)
    val unit = side / 100f
    val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    val bodyTransform: (BlobatarPoint) -> BlobatarPoint = { point ->
        BlobatarPoint(
            x = 50.0 + (point.x - 50.0 + frame.shake.x) * frame.breathe.x,
            y = 50.0 + (point.y - 50.0 + frame.shake.y) * frame.breathe.y + frame.bob + pose.bodyOffsetY,
        )
    }

    blobatarBackdropPath(resolved.backdrop)?.let {
        drawBlobatarPath(it, colorFromHex(palette.background), origin, unit) { point ->
            BlobatarPoint(point.x, point.y)
        }
    }

    val head = colorFromHex(palette.head)
    resolved.layout.petals.forEach { petal ->
        drawBlobatarPath(
            circlePath(petal.cx, petal.cy, petal.radius),
            head,
            origin,
            unit,
            bodyTransform,
        )
    }
    resolved.layout.extras.forEach { extra ->
        drawBlobatarPath(extra, head, origin, unit, bodyTransform)
    }
    drawBlobatarPath(resolved.layout.bodyPath, head, origin, unit, bodyTransform)

    val eye = colorFromHex(palette.eye)
    resolved.layout.eyes.forEachIndexed { index, base ->
        val right = index == 1
        val sideSign = if (right) 1.0 else -1.0
        val selector = if (right) 1.0 else 0.0
        val rockShare = selector * (1.0 - pose.rock) +
            pose.rock * ((1.0 + sideSign * frame.rockPhase) / 2.0)
        val eyeGeometry = BlobatarEye(
            cx = base.cx + pose.eyeOffsetX * sideSign + frame.saccade.x,
            cy = base.cy + pose.eyeOffsetY + selector * pose.rightEyeOffsetY * rockShare + frame.saccade.y,
            rx = base.rx * (pose.eyeScaleX + selector * pose.rightEyeScaleX) * eyeWrapX(frame, sideSign),
            ry = base.ry * (pose.eyeScaleY + selector * pose.rightEyeScaleY) * frame.blink * eyeWrapY(frame),
            n = base.n,
            rotation = base.rotation * (1.0 - pose.lockSeededTilt) +
                (pose.tilt + selector * pose.rightEyeTilt) * sideSign + eyeWrapRotation(frame, sideSign),
        )
        drawBlobatarPath(
            superellipsePath(
                eyeGeometry.cx,
                eyeGeometry.cy,
                eyeGeometry.rx.coerceAtLeast(0.01),
                eyeGeometry.ry.coerceAtLeast(0.01),
                eyeGeometry.n,
                eyeGeometry.rotation,
            ),
            eye,
            origin,
            unit,
            bodyTransform,
        )
    }
}

/** Convenience overload that resolves the deterministic core at the draw site. */
fun DrawScope.drawBlobatar(
    seed: String,
    options: BlobatarOptions = BlobatarOptions(),
    expression: BlobatarExpression = BlobatarExpression.Idle,
    frame: BlobatarMotionFrame = BlobatarMotionFrame(),
) = drawBlobatar(resolveBlobatar(seed, options), expression, frame)

private fun DrawScope.drawBlobatarPath(
    source: BlobatarPath,
    color: Color,
    origin: Offset,
    unit: Float,
    transform: (BlobatarPoint) -> BlobatarPoint,
) {
    val path = Path()
    source.commands.forEach { command ->
        when (command) {
            is BlobatarPathCommand.Move -> path.moveToCanvas(command.point, transform, origin, unit)
            is BlobatarPathCommand.Line -> path.lineToCanvas(command.point, transform, origin, unit)
            is BlobatarPathCommand.Quadratic -> path.quadraticToCanvas(
                command.control,
                command.end,
                transform,
                origin,
                unit,
            )
            is BlobatarPathCommand.Cubic -> path.cubicToCanvas(
                command.firstControl,
                command.secondControl,
                command.end,
                transform,
                origin,
                unit,
            )
            BlobatarPathCommand.Close -> path.close()
        }
    }
    drawPath(path, color)
}

private fun Path.moveToCanvas(
    point: BlobatarPoint,
    transform: (BlobatarPoint) -> BlobatarPoint,
    origin: Offset,
    unit: Float,
) {
    val value = transform(point)
    moveTo(origin.x + value.x.toFloat() * unit, origin.y + value.y.toFloat() * unit)
}

private fun Path.lineToCanvas(
    point: BlobatarPoint,
    transform: (BlobatarPoint) -> BlobatarPoint,
    origin: Offset,
    unit: Float,
) {
    val value = transform(point)
    lineTo(origin.x + value.x.toFloat() * unit, origin.y + value.y.toFloat() * unit)
}

private fun Path.quadraticToCanvas(
    control: BlobatarPoint,
    end: BlobatarPoint,
    transform: (BlobatarPoint) -> BlobatarPoint,
    origin: Offset,
    unit: Float,
) {
    val first = transform(control)
    val last = transform(end)
    quadraticTo(
        origin.x + first.x.toFloat() * unit,
        origin.y + first.y.toFloat() * unit,
        origin.x + last.x.toFloat() * unit,
        origin.y + last.y.toFloat() * unit,
    )
}

private fun Path.cubicToCanvas(
    firstControl: BlobatarPoint,
    secondControl: BlobatarPoint,
    end: BlobatarPoint,
    transform: (BlobatarPoint) -> BlobatarPoint,
    origin: Offset,
    unit: Float,
) {
    val first = transform(firstControl)
    val second = transform(secondControl)
    val last = transform(end)
    cubicTo(
        origin.x + first.x.toFloat() * unit,
        origin.y + first.y.toFloat() * unit,
        origin.x + second.x.toFloat() * unit,
        origin.y + second.y.toFloat() * unit,
        origin.x + last.x.toFloat() * unit,
        origin.y + last.y.toFloat() * unit,
    )
}

private fun circlePath(cx: Double, cy: Double, radius: Double): BlobatarPath =
    superellipsePath(cx, cy, radius, radius, 2.0, 0.0)

private fun eyeWrapX(frame: BlobatarMotionFrame, side: Double): Double =
    1.0 + frame.wrap.mx + frame.wrap.side * side

private fun eyeWrapY(frame: BlobatarMotionFrame): Double = 1.0 + frame.wrap.sy

private fun eyeWrapRotation(frame: BlobatarMotionFrame, side: Double): Double =
    frame.wrap.rotation * side

private fun colorFromHex(hex: String): Color {
    val value = hex.removePrefix("#").toLong(16)
    return Color(
        red = ((value shr 16) and 0xffL).toInt() / 255f,
        green = ((value shr 8) and 0xffL).toInt() / 255f,
        blue = (value and 0xffL).toInt() / 255f,
        alpha = 1f,
    )
}
