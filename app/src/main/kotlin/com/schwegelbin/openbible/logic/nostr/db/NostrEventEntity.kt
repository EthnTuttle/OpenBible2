package com.schwegelbin.openbible.logic.nostr.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a stored Nostr event.
 * Indexes on kind and pubkey for efficient filter queries.
 */
@Entity(
    tableName = "nostr_events",
    indices = [
        Index(value = ["kind"]),
        Index(value = ["pubkey"]),
        Index(value = ["created_at"])
    ]
)
data class NostrEventEntity(
    @PrimaryKey
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: String,       // JSON-encoded List<List<String>>
    val content: String,
    val sig: String
)

/**
 * Room entity for indexing tags (many-to-one relationship with events).
 * This enables efficient queries like "all events with tag #r = bible:kjv/john/3/16".
 */
@Entity(
    tableName = "nostr_event_tags",
    primaryKeys = ["event_id", "tag_name", "tag_value"],
    indices = [
        Index(value = ["tag_name", "tag_value"]),
        Index(value = ["event_id"])
    ]
)
data class NostrEventTagEntity(
    val event_id: String,
    val tag_name: String,
    val tag_value: String
)

/**
 * Room entity tracking which events have been published to which relays.
 * Used to show "published" vs "local only" status.
 */
@Entity(
    tableName = "published_events",
    primaryKeys = ["event_id", "relay_url"]
)
data class PublishedEventEntity(
    val event_id: String,
    val relay_url: String,
    val published_at: Long = System.currentTimeMillis()
)
