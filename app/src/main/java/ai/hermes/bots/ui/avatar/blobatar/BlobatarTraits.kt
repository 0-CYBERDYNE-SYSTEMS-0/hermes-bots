package ai.hermes.bots.ui.avatar.blobatar

/** A fixed trait position or a seed-selected narrowing list, in upstream units [0, 1). */
sealed interface BlobatarTraitOverride {
    data class Fixed(val value: Double) : BlobatarTraitOverride
    data class OneOf(val values: List<Double>) : BlobatarTraitOverride
}

fun fixedTrait(value: Double): BlobatarTraitOverride = BlobatarTraitOverride.Fixed(value)
fun oneOfTraits(vararg values: Double): BlobatarTraitOverride = BlobatarTraitOverride.OneOf(values.toList())

/** Independent keyed trait reader from upstream Blobatar v2.7.0 `src/traits.ts`. */
class BlobatarTraits(
    seed: String,
    normalize: Boolean = true,
    private val overrides: Map<String, BlobatarTraitOverride> = emptyMap(),
) {
    private val state = blobatarSeedState(seed, normalize)

    operator fun invoke(key: String): Double {
        val hashed = blobatarStream(state, key)
        val selected = when (val override = overrides[key]) {
            is BlobatarTraitOverride.Fixed -> override.value
            is BlobatarTraitOverride.OneOf -> override.values
                .takeUnless { it.isEmpty() }
                ?.let { it[(hashed * it.size).toInt()] }
            null -> null
        } ?: return hashed
        return when {
            selected.isNaN() || selected <= 0.0 -> 0.0
            selected < 1.0 -> selected
            else -> 0.999999
        }
    }

    fun number(key: String, min: Double, max: Double): Double = min + invoke(key) * (max - min)

    fun integer(key: String, min: Int, max: Int): Int = min + (invoke(key) * (max - min + 1)).toInt()

    fun <T> pick(key: String, options: List<T>): T = options[(invoke(key) * options.size).toInt()]

    fun boolean(key: String, probability: Double = 0.5): Boolean = invoke(key) < probability

    fun jitter(key: String, amount: Double): Double = (invoke(key) * 2.0 - 1.0) * amount
}
