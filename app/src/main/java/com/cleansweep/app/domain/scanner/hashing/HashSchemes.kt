package com.cleansweep.app.domain.scanner.hashing

/**
 * The two hash schemes whose values are stored in `file_index`, and the buffer sizes they read with.
 *
 * A stored value is `<scheme>:<hex>`. The scheme is part of the value, so a value written by a build that used a
 * different scheme is recognised as stale and recomputed instead of being compared with fresh ones.
 */
object HashSchemes {
    /** Stage 3: SHA-256 over the whole file. */
    const val FULL: String = "sha256"

    /** Stage 2: SHA-256 over the first and the last 4 KB of the file. */
    const val PARTIAL: String = "sha256-head4k-tail4k"

    /** Fixed read buffer of the full hash: memory use does not grow with the size of the file. */
    const val STREAM_BUFFER_BYTES: Int = 64 * 1024

    /** Size of each of the two chunks the partial hash covers. */
    const val PARTIAL_CHUNK_BYTES: Int = 4 * 1024

    /** At or below this size the two chunks of the partial hash cover the whole file. */
    val WHOLE_FILE_LIMIT_BYTES: Long = 2L * PARTIAL_CHUNK_BYTES

    private const val SHA256_HEX_LENGTH: Int = 64
    private const val HEX_DIGITS: String = "0123456789abcdef"

    fun encode(scheme: String, digest: ByteArray): String = "$scheme:${digest.toLowerHex()}"

    /** False for null, for a partial hash and for a value that is truncated or of another scheme. */
    fun isFull(value: String?): Boolean = hasScheme(value, FULL)

    /** False for null, for a full hash and for a value that is truncated or of another scheme. */
    fun isPartial(value: String?): Boolean = hasScheme(value, PARTIAL)

    private fun hasScheme(value: String?, scheme: String): Boolean =
        value != null &&
            value.startsWith("$scheme:") &&
            value.length == scheme.length + 1 + SHA256_HEX_LENGTH

    private fun ByteArray.toLowerHex(): String {
        val out = CharArray(size * 2)
        for (index in indices) {
            val byte = this[index].toInt() and 0xFF
            out[index * 2] = HEX_DIGITS[byte ushr 4]
            out[index * 2 + 1] = HEX_DIGITS[byte and 0x0F]
        }
        return String(out)
    }
}
