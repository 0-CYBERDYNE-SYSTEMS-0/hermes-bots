package ai.hermes.bots.ui.avatar.blobatar

/**
 * The fourteen immutable generation-2 poses. Expressions are a separate axis from the seed:
 * changing one never changes the silhouette, palette, or idle geometry of a bot.
 */
enum class BlobatarExpression {
    Idle,
    Happy,
    Sad,
    Mad,
    Surprised,
    Wink,
    Sleepy,
    Smug,
    Unsure,
    Scared,
    Love,
    Shy,
    Sick,
    Thinking,
}

/** A renderer-neutral pose adjustment expressed as multipliers in Blobatar's 100-unit space. */
data class BlobatarExpressionPose(
    val eyeScaleX: Double = 1.0,
    val eyeScaleY: Double = 1.0,
    val tilt: Double = 0.0,
    val eyeOffsetY: Double = 0.0,
    val eyeOffsetX: Double = 0.0,
    val rightEyeScaleX: Double = 0.0,
    val rightEyeScaleY: Double = 0.0,
    val rightEyeTilt: Double = 0.0,
    val rightEyeOffsetY: Double = 0.0,
    val lockSeededTilt: Double = 0.0,
    val heat: Double = 0.0,
    val shake: Double = 0.0,
    val rock: Double = 0.0,
    val bodyOffsetY: Double = 0.0,
) {
    /** Compatibility aliases that make simple pose inspection pleasant in tests/callers. */
    val eyeScale: Double get() = eyeScaleX
    val eyeHeight: Double get() = eyeScaleY
    val eyeGap: Double get() = 1.0 + eyeOffsetX
    val esx: Double get() = eyeScaleX
    val esy: Double get() = eyeScaleY
    val edy: Double get() = eyeOffsetY
    val edx: Double get() = eyeOffsetX
    val esx2: Double get() = rightEyeScaleX
    val esy2: Double get() = rightEyeScaleY
    val tilt2: Double get() = rightEyeTilt
    val edy2: Double get() = rightEyeOffsetY
    val lock: Double get() = lockSeededTilt
    val bdy: Double get() = bodyOffsetY
}

/** Resolve one upstream v2.7.0 expression. */
fun BlobatarExpression.pose(): BlobatarExpressionPose = when (this) {
    BlobatarExpression.Idle -> BlobatarExpressionPose()
    BlobatarExpression.Happy -> BlobatarExpressionPose(
        eyeScaleX = 1.72, eyeScaleY = 0.3, tilt = 8.0, eyeOffsetY = -1.5, eyeOffsetX = 1.5,
        rightEyeScaleX = 0.08, rightEyeScaleY = 0.05, rightEyeTilt = -16.0,
        lockSeededTilt = 1.0, bodyOffsetY = -2.2,
    )
    BlobatarExpression.Sad -> BlobatarExpressionPose(
        eyeScaleX = 0.6, eyeScaleY = 0.56, tilt = 26.0, eyeOffsetY = 3.6, eyeOffsetX = 1.9,
        rightEyeScaleX = -0.05, rightEyeScaleY = -0.07, rightEyeTilt = -7.0,
        lockSeededTilt = 1.0, bodyOffsetY = 2.6,
    )
    BlobatarExpression.Mad -> BlobatarExpressionPose(
        eyeScaleX = 1.85, eyeScaleY = 0.26, tilt = -33.0, eyeOffsetY = 0.4, eyeOffsetX = 0.6,
        rightEyeScaleY = -0.03, rightEyeTilt = 5.0, lockSeededTilt = 1.0,
        heat = 0.62, shake = 0.55, bodyOffsetY = 0.8,
    )
    BlobatarExpression.Surprised -> BlobatarExpressionPose(
        eyeScaleX = 1.34, eyeScaleY = 1.2, tilt = -6.0, eyeOffsetY = -1.05, eyeOffsetX = 0.5,
        rightEyeScaleX = 0.05, rightEyeScaleY = 0.07, rightEyeTilt = 3.0,
        lockSeededTilt = 1.0, bodyOffsetY = -1.4,
    )
    BlobatarExpression.Wink -> BlobatarExpressionPose(
        eyeScaleX = 1.32, eyeScaleY = 0.76, tilt = 5.0, eyeOffsetY = -0.6, eyeOffsetX = 0.8,
        rightEyeScaleX = 0.26, rightEyeScaleY = -0.56, rightEyeTilt = -11.0,
        lockSeededTilt = 1.0, bodyOffsetY = -1.1,
    )
    BlobatarExpression.Sleepy -> BlobatarExpressionPose(
        eyeScaleX = 1.14, eyeScaleY = 0.22, eyeOffsetY = 2.4, eyeOffsetX = 0.3,
        rightEyeScaleX = -0.04, rightEyeScaleY = 0.03, rightEyeTilt = 4.0,
        lockSeededTilt = 1.0, bodyOffsetY = 1.2,
    )
    BlobatarExpression.Smug -> BlobatarExpressionPose(
        eyeScaleX = 1.3, eyeScaleY = 0.42, tilt = 18.0, eyeOffsetY = -0.5, eyeOffsetX = 0.5,
        rightEyeScaleX = 0.06, rightEyeScaleY = -0.06, rightEyeTilt = -36.0,
        lockSeededTilt = 1.0, bodyOffsetY = -1.0,
    )
    BlobatarExpression.Unsure -> BlobatarExpressionPose(
        eyeScaleX = 0.95, eyeScaleY = 1.02, tilt = 4.0, eyeOffsetY = -0.2, eyeOffsetX = 0.3,
        rightEyeScaleX = 0.24, rightEyeScaleY = -0.44, rightEyeTilt = -18.0,
        lockSeededTilt = 1.0,
    )
    BlobatarExpression.Scared -> BlobatarExpressionPose(
        eyeScaleX = 0.78, eyeScaleY = 0.96, tilt = -12.0, eyeOffsetY = -1.5, eyeOffsetX = -0.8,
        rightEyeScaleX = -0.04, rightEyeScaleY = 0.05, rightEyeTilt = 4.0,
        lockSeededTilt = 1.0, shake = 0.35, bodyOffsetY = -0.6,
    )
    BlobatarExpression.Love -> BlobatarExpressionPose(
        eyeScaleX = 0.86, eyeScaleY = 1.28, tilt = -14.0, eyeOffsetY = -0.5, eyeOffsetX = -0.35,
        rightEyeScaleX = 0.05, rightEyeScaleY = 0.06, rightEyeTilt = 6.0,
        lockSeededTilt = 1.0, heat = 0.6, bodyOffsetY = -1.6,
    )
    BlobatarExpression.Shy -> BlobatarExpressionPose(
        eyeScaleX = 0.62, eyeScaleY = 0.5, tilt = 10.0, eyeOffsetY = 1.4, eyeOffsetX = -0.2,
        rightEyeScaleX = -0.05, rightEyeScaleY = -0.04, rightEyeTilt = -8.0,
        lockSeededTilt = 1.0, heat = 0.55, bodyOffsetY = 0.9,
    )
    BlobatarExpression.Sick -> BlobatarExpressionPose(
        eyeScaleX = 1.25, eyeScaleY = 0.34, tilt = 20.0, eyeOffsetY = 1.8, eyeOffsetX = 0.8,
        rightEyeScaleX = 0.05, rightEyeScaleY = -0.05, rightEyeTilt = -6.0,
        lockSeededTilt = 1.0, heat = 0.6, shake = 0.18, bodyOffsetY = 1.4,
    )
    BlobatarExpression.Thinking -> BlobatarExpressionPose(
        eyeScaleX = 1.15, eyeScaleY = 0.62, eyeOffsetY = 4.2, eyeOffsetX = 0.4,
        rightEyeScaleX = 0.02, rightEyeScaleY = 0.06, rightEyeOffsetY = -8.4,
        lockSeededTilt = 1.0, rock = 0.8, bodyOffsetY = -0.4,
    )
}

/** Explicit name for callers that prefer a free function over the enum extension. */
fun blobatarExpressionPose(expression: BlobatarExpression): BlobatarExpressionPose = expression.pose()
