package com.schwegelbin.openbible.logic.nostr.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface NostrEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: NostrEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<NostrEventTagEntity>)

    /**
     * Insert an event and its associated tag index entries atomically.
     */
    @Transaction
    suspend fun insertEventWithTags(event: NostrEventEntity, tags: List<NostrEventTagEntity>) {
        insertEvent(event)
        insertTags(tags)
    }

    @Query("SELECT * FROM nostr_events WHERE id = :id")
    suspend fun getEventById(id: String): NostrEventEntity?

    @Query("SELECT * FROM nostr_events WHERE kind = :kind ORDER BY created_at DESC")
    suspend fun getEventsByKind(kind: Int): List<NostrEventEntity>

    @Query("SELECT * FROM nostr_events WHERE pubkey = :pubkey AND kind = :kind ORDER BY created_at DESC")
    suspend fun getEventsByAuthorAndKind(pubkey: String, kind: Int): List<NostrEventEntity>

    /**
     * Get events matching a specific tag value.
     * For example: tag_name = "r", tag_value = "bible:kjv/john/3/16"
     */
    @Query("""
        SELECT e.* FROM nostr_events e
        INNER JOIN nostr_event_tags t ON e.id = t.event_id
        WHERE e.kind = :kind AND t.tag_name = :tagName AND t.tag_value = :tagValue
        ORDER BY e.created_at DESC
    """)
    suspend fun getEventsByKindAndTag(
        kind: Int,
        tagName: String,
        tagValue: String
    ): List<NostrEventEntity>

    /**
     * Get events matching a tag value prefix (for chapter-level queries).
     * For example: tag_value LIKE 'bible:kjv/john/3/%'
     */
    @Query("""
        SELECT e.* FROM nostr_events e
        INNER JOIN nostr_event_tags t ON e.id = t.event_id
        WHERE e.kind = :kind AND t.tag_name = :tagName AND t.tag_value LIKE :tagValuePrefix || '%'
        ORDER BY e.created_at DESC
    """)
    suspend fun getEventsByKindAndTagPrefix(
        kind: Int,
        tagName: String,
        tagValuePrefix: String
    ): List<NostrEventEntity>

    @Query("SELECT * FROM nostr_events ORDER BY created_at DESC")
    suspend fun getAllEvents(): List<NostrEventEntity>

    @Query("SELECT * FROM nostr_events WHERE kind = :kind")
    suspend fun getAllEventsByKind(kind: Int): List<NostrEventEntity>

    @Query("SELECT id, created_at FROM nostr_events WHERE kind = :kind ORDER BY created_at ASC")
    suspend fun getEventIdsAndTimestampsByKind(kind: Int): List<EventIdTimestamp>

    @Query("DELETE FROM nostr_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Query("DELETE FROM nostr_event_tags WHERE event_id = :eventId")
    suspend fun deleteEventTags(eventId: String)

    /**
     * Delete an event and its tag entries atomically.
     */
    @Transaction
    suspend fun deleteEventWithTags(eventId: String) {
        deleteEventTags(eventId)
        deleteEvent(eventId)
    }

    @Query("SELECT COUNT(*) FROM nostr_events")
    suspend fun getEventCount(): Int

    @Query("SELECT COUNT(*) FROM nostr_events WHERE kind = :kind")
    suspend fun getEventCountByKind(kind: Int): Int

    // -- Published events tracking --

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPublishedEvent(published: PublishedEventEntity)

    @Query("SELECT relay_url FROM published_events WHERE event_id = :eventId")
    suspend fun getPublishedRelays(eventId: String): List<String>

    @Query("SELECT COUNT(*) > 0 FROM published_events WHERE event_id = :eventId")
    suspend fun isEventPublished(eventId: String): Boolean

    @Query("DELETE FROM published_events WHERE event_id = :eventId")
    suspend fun deletePublishedRecords(eventId: String)
}

/**
 * Lightweight projection for Negentropy sync — only need id and timestamp.
 */
data class EventIdTimestamp(
    val id: String,
    val created_at: Long
)
