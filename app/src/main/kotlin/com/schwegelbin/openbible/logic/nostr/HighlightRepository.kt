package com.schwegelbin.openbible.logic.nostr

import android.content.Context
import com.schwegelbin.openbible.logic.nostr.db.NostrEventDao
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
    private val dao: NostrEventDao,
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
     * Includes published status for each highlight.
     */
    suspend fun getAllHighlights(): List<Highlight> {
        val filter = NostrFilter(kinds = listOf(HIGHLIGHT_KIND))
        return eventStore.queryEvents(filter).mapNotNull { event ->
            event.toHighlight()?.let { highlight ->
                val publishedRelays = dao.getPublishedRelays(highlight.eventId)
                highlight.copy(publishedRelays = publishedRelays)
            }
        }
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
     * Also publishes a kind 1 note quoting the highlight for broader visibility.
     * Returns list of relay URLs that successfully accepted the highlight event.
     */
    suspend fun publishHighlight(eventId: String, relayUrls: List<String>): List<String> {
        val sm = syncManager ?: return emptyList()
        
        // Publish the highlight event
        val publishedRelays = sm.publishToRelays(eventId, relayUrls)
        
        // If successful, also publish a kind 1 note quoting the highlight
        if (publishedRelays.isNotEmpty()) {
            val highlightEvent = eventStore.getEvent(eventId)
            val highlight = highlightEvent?.toHighlight()
            
            if (highlight != null && highlight.reference != null) {
                val relayHint = publishedRelays.firstOrNull()
                val quoteUnsigned = createHighlightQuoteNote(
                    pubkey = signer.publicKeyHex,
                    highlightEventId = eventId,
                    highlightedText = highlight.highlightedText,
                    reference = highlight.reference,
                    comment = highlight.comment,
                    relayHint = relayHint
                )
                
                val quoteSigned = signer.sign(quoteUnsigned)
                if (quoteSigned != null) {
                    // Store locally and publish
                    eventStore.storeEvent(quoteSigned)
                    sm.publishToRelays(quoteSigned.id, relayUrls)
                }
            }
        }
        
        return publishedRelays
    }

    /**
     * Publish all local highlights to public relays.
     * Returns the total number of successful publishes.
     */
    suspend fun publishAllHighlights(relayUrls: List<String>): Int {
        var successCount = 0
        val highlights = getAllHighlights()
        highlights.forEach { highlight ->
            val published = syncManager?.publishToRelays(highlight.eventId, relayUrls) ?: emptyList()
            successCount += published.size
        }
        return successCount
    }

    /**
     * Check if a highlight has been published.
     */
    suspend fun isPublished(eventId: String): Boolean {
        return dao.isEventPublished(eventId)
    }
}
