package com.druk.llamacpp

/**
 * Limits enforced by the AIDL proxy before sending data across the binder.
 *
 * Android binder transactions cap at ~1 MB total *per process* — and other
 * frameworks (notifications, lifecycle, dumpsys) compete for the same
 * buffer. We pick a safe per-string ceiling well below the kernel cap so
 * a single oversized payload cannot pre-empt unrelated binder traffic.
 *
 * The proxy validates inputs against [MAX_PAYLOAD_BYTES] and throws
 * [PayloadTooLargeException] before issuing the AIDL call, so the user
 * gets a clear error instead of a hard `TransactionTooLargeException`.
 */
object InferenceLimits {
    /** Conservative per-string ceiling for AIDL payloads. */
    const val MAX_PAYLOAD_BYTES: Int = 700 * 1024 // 700 KB

    /**
     * Validate that [text] fits inside the binder transaction budget.
     * Throws [PayloadTooLargeException] if it doesn't.
     *
     * **Why UTF-16, not UTF-8.** Android's `Parcel.writeString` serializes
     * Java/Kotlin `String` as UTF-16 (a 32-bit length prefix plus 2 bytes
     * per code unit, plus a trailing null). UTF-8 byte count
     * under-counts for ASCII payloads — a 600 KB ASCII message is only
     * 600 KB in UTF-8 but 1.2 MB on the binder wire, which would still
     * trip TransactionTooLargeException at the kernel boundary even
     * though our UTF-8 check passed.
     *
     * For non-BMP characters (emoji etc.) UTF-16 uses surrogate pairs
     * which `String.length` already counts as 2 code units, so this is
     * accurate everywhere.
     *
     * @param fieldName short label used in the exception message, e.g.
     *   `"system prompt"` or `"user message"`.
     */
    fun requireWithinBudget(text: String, fieldName: String) {
        // 2 bytes per UTF-16 code unit. Constant overhead (length prefix,
        // null terminator, alignment) is negligible vs the headroom we
        // leave under the 1 MB binder cap.
        val bytes = text.length * 2
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw PayloadTooLargeException(fieldName, bytes, MAX_PAYLOAD_BYTES)
        }
    }
}

/**
 * Thrown by the inference proxy when a string passed across the AIDL
 * boundary exceeds [InferenceLimits.MAX_PAYLOAD_BYTES]. UI layers should
 * catch this and surface a localized error to the user — sending it to
 * the binder would raise [android.os.TransactionTooLargeException]
 * synchronously and may destabilize unrelated binder traffic in the same
 * process.
 */
class PayloadTooLargeException(
    val fieldName: String,
    val sizeBytes: Int,
    val limitBytes: Int,
) : IllegalArgumentException(
    "$fieldName is ${sizeBytes / 1024} KB, exceeds the ${limitBytes / 1024} KB binder limit",
)
