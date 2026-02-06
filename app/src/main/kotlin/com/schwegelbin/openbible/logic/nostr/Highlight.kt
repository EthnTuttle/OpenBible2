package com.schwegelbin.openbible.logic.nostr

/**
 * NIP-84 Highlight event helpers.
 * Kind: 9802
 *
 * The .content of a highlight event is the highlighted text.
 * Tags:
 *   ["r", "bible:{translation}/{book}/{chapter}/{verse}", "source"]  — reference
 *   ["context", "full verse text"]                                    — context
 *   ["comment", "user's note"]                                        — optional note
 *   ["alt", "Highlight: ..."]                                         — alt text
 */

const val HIGHLIGHT_KIND = 9802
private const val BIBLE_URI_PREFIX = "bible:"

/**
 * A parsed Bible reference from a highlight's "r" tag.
 */
data class BibleReference(
    val translation: String,
    val book: String,
    val chapter: Int,
    val verse: Int
) {
    fun toUri(): String = "$BIBLE_URI_PREFIX$translation/$book/$chapter/$verse"

    /**
     * URI prefix for chapter-level queries (matches all verses in the chapter).
     */
    fun toChapterPrefix(): String = "$BIBLE_URI_PREFIX$translation/$book/$chapter/"

    companion object {
        /**
         * Parse a Bible reference URI.
         * Format: "bible:{translation}/{book}/{chapter}/{verse}"
         */
        fun fromUri(uri: String): BibleReference? {
            if (!uri.startsWith(BIBLE_URI_PREFIX)) return null
            val parts = uri.removePrefix(BIBLE_URI_PREFIX).split("/")
            if (parts.size < 4) return null
            return try {
                BibleReference(
                    translation = parts[0],
                    book = parts[1],
                    chapter = parts[2].toInt(),
                    verse = parts[3].toInt()
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * A user-facing highlight with parsed metadata.
 */
data class Highlight(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val highlightedText: String,
    val comment: String?,
    val context: String?,
    val reference: BibleReference?
)

/**
 * Create an unsigned NIP-84 highlight event.
 */
fun createHighlightEvent(
    pubkey: String,
    highlightedText: String,
    reference: BibleReference,
    context: String? = null,
    comment: String? = null
): UnsignedEvent {
    val tags = mutableListOf<List<String>>()

    // Source reference
    tags.add(listOf("r", reference.toUri(), "source"))

    // Context (surrounding verse text)
    if (!context.isNullOrBlank()) {
        tags.add(listOf("context", context))
    }

    // User's note/comment
    if (!comment.isNullOrBlank()) {
        tags.add(listOf("comment", comment))
    }

    // Alt text for clients that don't support NIP-84
    val altText = if (highlightedText.length > 100) {
        "Highlight: ${highlightedText.take(97)}..."
    } else {
        "Highlight: $highlightedText"
    }
    tags.add(listOf("alt", altText))

    return createUnsignedEvent(
        pubkey = pubkey,
        kind = HIGHLIGHT_KIND,
        tags = tags,
        content = highlightedText
    )
}

/**
 * Parse a NostrEvent into a Highlight domain object.
 */
fun NostrEvent.toHighlight(): Highlight? {
    if (kind != HIGHLIGHT_KIND) return null

    val referenceUri = tags.firstOrNull { it.size >= 2 && it[0] == "r" }?.get(1)
    val comment = tags.firstOrNull { it.size >= 2 && it[0] == "comment" }?.get(1)
    val context = tags.firstOrNull { it.size >= 2 && it[0] == "context" }?.get(1)

    return Highlight(
        eventId = id,
        pubkey = pubkey,
        createdAt = created_at,
        highlightedText = content,
        comment = comment,
        context = context,
        reference = referenceUri?.let { BibleReference.fromUri(it) }
    )
}
