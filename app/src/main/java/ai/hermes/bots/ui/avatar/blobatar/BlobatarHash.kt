package ai.hermes.bots.ui.avatar.blobatar

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Blobatar generation-2 hashing, ported from `src/hash.ts` at upstream v2.7.0
 * (`ebb7ea4808b1263629fc8fa65e2398b9cbdb6f6b`).
 */
private const val STREAM_SEPARATOR: Byte = 0xff.toByte()

fun normalizeBlobatarSeed(seed: String): String =
    Normalizer.normalize(seed, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)

fun blobatarSeedState(seed: String, normalize: Boolean = true): Int {
    val value = if (normalize) normalizeBlobatarSeed(seed) else seed
    return feed(1_779_033_703 xor value.length, value.toByteArray(StandardCharsets.UTF_8))
}

fun blobatarStream(state: Int, key: String): Double {
    val keyed = feed(feed(state, byteArrayOf(STREAM_SEPARATOR)), key.toByteArray(StandardCharsets.UTF_8))
    return Integer.toUnsignedLong(finalize(keyed)).toDouble() / 4_294_967_296.0
}

private fun feed(initial: Int, bytes: ByteArray): Int {
    var hash = initial
    for (byte in bytes) {
        hash = (hash xor (byte.toInt() and 0xff)) * -862_048_943
        hash = hash.rotateLeft(13)
    }
    return hash
}

private fun finalize(initial: Int): Int {
    var hash = (initial xor (initial ushr 16)) * -2_048_144_789
    hash = (hash xor (hash ushr 13)) * -1_028_477_387
    return hash xor (hash ushr 16)
}

private fun Int.rotateLeft(distance: Int): Int = (this shl distance) or (this ushr (32 - distance))
