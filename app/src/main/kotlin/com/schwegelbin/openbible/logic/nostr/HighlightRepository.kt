package com.schwegelbin.openbible.logic.nostr

import android.content.Context
import com.schwegelbin.openbible.logic.nostr.embedded.EmbeddedRelay
import com.schwegelbin.openbible.logic.nostr.embedded.EventStore
import com.schwegelbin.openbible.logic.nostr.sync.SyncManager

/**
 * Facade for highlight CRUD operations.
 * UI code uses this instead of talking to relays directly.
 * All operations go through the embedded relay's EventStore.
 */
class HighlightRepository(
    private val embeddedRelay: EmbeddedRelay,
    private val signer: Signer,
    private val syncManager: SyncManager? = null
) {
    private val eventStore: EventStore = embeddedRelay.getEventStore()

    /**
     * Create and store a new highlight.
     * Returns the created Highlight, or null if signing failed.
     */
    suspend fun saveHighlight(
        highlightedText: String,
        reference: BibleReference,
        context: String? = null,
        comment: String? = null
    ): Highlight? {
        val unsigned = createHighlightEvent(
            pubkey = signer.publicKeyHex,
            highlightedText = highlightedText,
            reference = reference,
            context = context,
            comment = comment
        )

        val signed = signer.sign(unsigned) ?: return null
        eventStore.storeEvent(signed)
        return signed.toHighlight()
    }

    /**
     * Get all highlights for a specific chapter.
     * Queries by the r tag prefix: "bible:{translation}/{book}/{chapter}/"
     */
    suspend fun getHighlightsForChapter(
        translation: String,
        bookName: String,
        chapter: Int
    ): List<Highlight> {
        val prefix = "bible:$translation/$bookName/$chapter/"
        val filter = NostrFilter(
            kinds = listOf(HIGHLIGHT_KIND),
            tags = mapOf("r" to listOf(prefix))
        )

        // Use tag prefix query for efficiency
        return eventStore.queryEvents(filter).mapNotNull { it.toHighlight() }
    }

    /**
     * Get all highlights for a specific verse.
     */
    suspend fun getHighlightsForVerse(
        translation: String,
        bookName: String,
        chapter: Int,
        verse: Int
    ): List<Highlight> {
        val ref = BibleReference(translation, bookName, chapter, verse)
        val filter = NostrFilter(
            kinds = listOf(HIGHLIGHT_KIND),
            tags = mapOf("r" to listOf(ref.toUri()))
        )
        return eventStore.queryEvents(filter).mapNotNull { it.toHighlight() }
    }

    /**
     * Get all highlights, grouped by reference.
     */
    suspend fun getAllHighlights(): List<Highlight> {
        val filter = NostrFilter(kinds = listOf(HIGHLIGHT_KIND))
        return eventStore.queryEvents(filter).mapNotNull { it.toHighlight() }
    }

    /**
     * Delete a highlight by creating a NIP-09 deletion event.
     * The EventStore.processDeletion will remove the original event
     * if the deletion event's pubkey matches.
     */
    suspend fun deleteHighlight(eventId: String): Boolean {
        val unsigned = createDeletionEvent(
            pubkey = signer.publicKeyHex,
            eventIds = listOf(eventId)
        )

        val signed = signer.sign(unsigned) ?: return false
        eventStore.processDeletion(signed)
        return true
    }

    /**
     * Publish a highlight to public relays.
     */
    suspend fun publishHighlight(eventId: String, relayUrls: List<String>) {
        syncManager?.publishToRelays(eventId, relayUrls)
    }

    /**
     * Publish all local highlights to public relays.
     */
    suspend fun publishAllHighlights(relayUrls: List<String>) {
        val highlights = getAllHighlights()
        highlights.forEach { highlight ->
            syncManager?.publishToRelays(highlight.eventId, relayUrls)
        }
    }
}
