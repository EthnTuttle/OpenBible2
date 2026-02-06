package com.schwegelbin.openbible.logic.nostr.embedded

import com.schwegelbin.openbible.logic.nostr.NostrFilter

/**
 * Tracks active REQ subscriptions for the embedded relay.
 * Maps subscription IDs to their filters and associated WebSocket sessions.
 */
class SubscriptionManager {

    data class Subscription(
        val id: String,
        val filter: NostrFilter,
        val sessionId: String
    )

    private val subscriptions = mutableMapOf<String, Subscription>()

    fun addSubscription(subId: String, filter: NostrFilter, sessionId: String) {
        subscriptions[subId] = Subscription(subId, filter, sessionId)
    }

    fun removeSubscription(subId: String) {
        subscriptions.remove(subId)
    }

    fun removeAllForSession(sessionId: String) {
        subscriptions.entries.removeAll { it.value.sessionId == sessionId }
    }

    fun getSubscription(subId: String): Subscription? = subscriptions[subId]

    /**
     * Get all subscriptions that match a given event.
     * Used to push new events to subscribers.
     */
    fun getMatchingSubscriptions(
        kind: Int,
        pubkey: String,
        tags: Map<String, List<String>>
    ): List<Subscription> {
        return subscriptions.values.filter { sub ->
            matchesFilter(sub.filter, kind, pubkey, tags)
        }
    }

    private fun matchesFilter(
        filter: NostrFilter,
        kind: Int,
        pubkey: String,
        tags: Map<String, List<String>>
    ): Boolean {
        if (filter.kinds != null && kind !in filter.kinds) return false
        if (filter.authors != null && pubkey !in filter.authors) return false
        filter.tags?.forEach { (tagName, filterValues) ->
            val eventValues = tags[tagName] ?: return false
            if (filterValues.none { it in eventValues }) return false
        }
        return true
    }
}
