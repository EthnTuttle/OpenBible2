package com.schwegelbin.openbible.logic.nostr.embedded

import com.schwegelbin.openbible.logic.nostr.NostrEvent
import com.schwegelbin.openbible.logic.nostr.NostrFilter
import com.schwegelbin.openbible.logic.nostr.db.NostrEventDao
import com.schwegelbin.openbible.logic.nostr.db.NostrEventEntity
import com.schwegelbin.openbible.logic.nostr.db.NostrEventTagEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wraps Room DAO operations for the embedded relay, converting between
 * Nostr event domain objects and Room entities.
 */
class EventStore(private val dao: NostrEventDao) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Store an event and index its tags.
     * Returns true if stored successfully, false if it already exists.
     */
    suspend fun storeEvent(event: NostrEvent): Boolean {
        // Check for duplicates
        if (dao.getEventById(event.id) != null) return false

        val entity = NostrEventEntity(
            id = event.id,
            pubkey = event.pubkey,
            created_at = event.created_at,
            kind = event.kind,
            tags = json.encodeToString(event.tags),
            content = event.content,
            sig = event.sig
        )

        val tagEntities = event.tags.mapNotNull { tag ->
            if (tag.size >= 2) {
                NostrEventTagEntity(
                    event_id = event.id,
                    tag_name = tag[0],
                    tag_value = tag[1]
                )
            } else null
        }

        dao.insertEventWithTags(entity, tagEntities)
        return true
    }

    /**
     * Process a NIP-09 deletion event (kind:5).
     * Deletes referenced events if they share the same pubkey.
     */
    suspend fun processDeletion(deletionEvent: NostrEvent): List<String> {
        val deletedIds = mutableListOf<String>()
        deletionEvent.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "e") {
                val targetId = tag[1]
                val target = dao.getEventById(targetId)
                if (target != null && target.pubkey == deletionEvent.pubkey) {
                    dao.deleteEventWithTags(targetId)
                    deletedIds.add(targetId)
                }
            }
        }
        // Also store the deletion event itself
        storeEvent(deletionEvent)
        return deletedIds
    }

    /**
     * Query events matching a NIP-01 filter.
     */
    suspend fun queryEvents(filter: NostrFilter): List<NostrEvent> {
        val entities = when {
            // Query by specific IDs
            filter.ids != null -> {
                filter.ids.mapNotNull { dao.getEventById(it) }
            }
            // Query by kind + tag
            filter.kinds != null && filter.tags != null -> {
                val kind = filter.kinds.first()
                val (tagName, tagValues) = filter.tags.entries.first()
                tagValues.flatMap { tagValue ->
                    dao.getEventsByKindAndTag(kind, tagName, tagValue)
                }.distinctBy { it.id }
            }
            // Query by kind + author
            filter.kinds != null && filter.authors != null -> {
                filter.authors.flatMap { author ->
                    filter.kinds.flatMap { kind ->
                        dao.getEventsByAuthorAndKind(author, kind)
                    }
                }.distinctBy { it.id }
            }
            // Query by kind only
            filter.kinds != null -> {
                filter.kinds.flatMap { kind ->
                    dao.getEventsByKind(kind)
                }.distinctBy { it.id }
            }
            // All events
            else -> dao.getAllEvents()
        }

        return entities
            .filter { entity ->
                (filter.since == null || entity.created_at >= filter.since) &&
                    (filter.until == null || entity.created_at <= filter.until)
            }
            .let { list ->
                if (filter.limit != null) list.take(filter.limit) else list
            }
            .map { it.toNostrEvent() }
    }

    /**
     * Get an event by its ID.
     */
    suspend fun getEvent(id: String): NostrEvent? {
        return dao.getEventById(id)?.toNostrEvent()
    }

    /**
     * Get all event IDs and timestamps for a given kind (used by Negentropy).
     */
    suspend fun getEventIdsAndTimestamps(kind: Int) = dao.getEventIdsAndTimestampsByKind(kind)

    /**
     * Get total event count.
     */
    suspend fun getEventCount(): Int = dao.getEventCount()

    private fun NostrEventEntity.toNostrEvent(): NostrEvent {
        val parsedTags: List<List<String>> = try {
            json.decodeFromString(tags)
        } catch (_: Exception) {
            emptyList()
        }
        return NostrEvent(
            id = id,
            pubkey = pubkey,
            created_at = created_at,
            kind = kind,
            tags = parsedTags,
            content = content,
            sig = sig
        )
    }
}
