package com.druk.lmplayground.rag

import android.content.Context
import android.net.Uri

/**
 * Pulls plain text out of an attached document. One implementation per
 * format family; see [DocumentTextExtractors] for routing.
 */
interface DocumentTextExtractor {

    fun supports(mimeType: String?, displayName: String): Boolean

    /**
     * Extract the document's text. Implementations stream from
     * [Context.getContentResolver] and enforce [MAX_EXTRACTED_CHARS]
     * (over-long documents are truncated, not rejected — a partial index
     * is more useful than an error).
     */
    @Throws(DocumentExtractionException::class)
    fun extract(context: Context, uri: Uri): String

    companion object {
        /** Files bigger than this are rejected before extraction. */
        const val MAX_FILE_BYTES = 30L * 1024 * 1024

        /** Extraction stops after this many characters (~200K tokens). */
        const val MAX_EXTRACTED_CHARS = 800_000

        /** PDF page cap — bounds extraction time on huge documents. */
        const val MAX_PDF_PAGES = 300
    }
}

class DocumentExtractionException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** Password-protected document. */
        ENCRYPTED,

        /** No extractable text (e.g. a scanned/image-only PDF). */
        NO_TEXT,

        /** File exceeds [DocumentTextExtractor.MAX_FILE_BYTES]. */
        TOO_LARGE,

        /** No extractor for this format. */
        UNSUPPORTED,

        /** The file couldn't be parsed (corrupt or unreadable). */
        PARSE_FAILED,
    }
}
