package com.schwegelbin.openbible.logic.nostr.sync

import android.content.Context
import android.util.Log
import com.schwegelbin.openbible.logic.nostr.NostrFilter
import com.schwegelbin.openbible.logic.nostr.Relay
import com.schwegelbin.openbible.logic.nostr.RelayMessage
import com.schwegelbin.openbible.logic.nostr.RelayPool
import com.schwegelbin.openbible.logic.nostr.RelayState
import com.schwegelbin.openbible.logic.nostr.db.NostrEventDao
import com.schwegelbin.openbible.logic.nostr.db.PublishedEventEntity
import com.schwegelbin.openbible.logic.nostr.embedded.EmbeddedRelay
import com.schwegelbin.openbible.logic.nostr.embedded.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SyncManager"
private const val HIGHLIGHT_KIND = 9802
private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L  // 15 minutes

/**
 * Orchestrates Negentropy sync across the three relay tiers:
 * embedded <-> external local, and embedded <-> public relays.
 */
class SyncManager(
    private val embeddedRelay: EmbeddedRelay,
    private val relayPool: RelayPool,
    private val dao: NostrEventDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val eventStore: EventStore = embeddedRelay.getEventStore()
    private val highlightFilter = NostrFilter(kinds = listOf(HIGHLIGHT_KIND))

    /**
     * Start periodic sync with all configured relays.
     */
    fun startPeriodicSync() {
        scope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                syncWithAllRelays()
            }
        }
    }

    /**
     * Sync with all connected relays now.
     */
    suspend fun syncWithAllRelays() {
        relayPool.getConnectedRelayUrls().forEach { url ->
            relayPool.getRelay(url)?.let { relay ->
                syncWithRelay(relay)
            }
        }
    }

    /**
     * Sync with a specific relay using Negentropy.
     * 1. Reconcile to find differences
     * 2. Upload events the remote needs (haveIds)
     * 3. Download events we need (needIds)
     */
    suspend fun syncWithRelay(relay: Relay) {
        if (relay.currentState != RelayState.CONNECTED) {
            Log.d(TAG, "Skipping sync with ${relay.url}: not connected")
            return
        }

        Log.d(TAG, "Starting sync with ${relay.url}")

        val neSync = NegentropySync(eventStore, relay)
        val result = neSync.reconcile(highlightFilter)

        if (!result.success) {
            Log.w(TAG, "Negentropy sync failed with ${relay.url}: ${result.error}")
            // Fall back to simple time-based sync if Negentropy is not supported
            if (result.error?.startsWith("blocked:") == true ||
                result.error?.contains("NEG") == true
            ) {
                Log.d(TAG, "Relay ${relay.url} may not support NIP-77, skipping")
            }
            return
        }

        // Upload events the remote needs
        if (result.haveIds.isNotEmpty()) {
            Log.d(TAG, "Uploading ${result.haveIds.size} events to ${relay.url}")
            result.haveIds.forEach { id ->
                eventStore.getEvent(id)?.let { event ->
                    relay.sendEvent(event)
                }
            }
        }

        // Download events we need
        if (result.needIds.isNotEmpty()) {
            Log.d(TAG, "Downloading ${result.needIds.size} events from ${relay.url}")
            val subId = relay.sendReq(NostrFilter(ids = result.needIds.toList()))

            // Collect events until EOSE
            scope.launch {
                relay.messages.collect { msg ->
                    when (msg) {
                        is RelayMessage.EventMsg -> {
                            if (msg.subscriptionId == subId) {
                                eventStore.storeEvent(msg.event)
                            }
                        }
                        is RelayMessage.EoseMsg -> {
                            if (msg.subscriptionId == subId) {
                                relay.sendClose(subId)
                                return@collect
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        Log.d(TAG, "Sync complete with ${relay.url}")
    }

    /**
     * Publish a specific event to given relay URLs.
     * Used when user explicitly publishes a highlight.
     * Returns list of relay URLs that successfully accepted the event.
     */
    suspend fun publishToRelays(eventId: String, relayUrls: List<String>): List<String> {
        val event = eventStore.getEvent(eventId) ?: run {
            Log.w(TAG, "Event $eventId not found for publishing")
            return emptyList()
        }

        val successfulRelays = mutableListOf<String>()

        relayUrls.forEach { url ->
            val relay = relayPool.getRelay(url)
            if (relay == null) {
                Log.w(TAG, "Relay not found in pool: $url")
                return@forEach
            }

            // Connect if not connected
            if (relay.currentState != RelayState.CONNECTED) {
                relay.connect()
                // Wait for connection with timeout
                val connected = withTimeoutOrNull(5000L) {
                    relay.state.first { it == RelayState.CONNECTED }
                }
                if (connected == null) {
                    Log.w(TAG, "Failed to connect to $url")
                    return@forEach
                }
            }

            // Send the event
            val sent = relay.sendEvent(event)
            if (!sent) {
                Log.w(TAG, "Failed to send event to $url")
                return@forEach
            }

            // Wait for OK response with timeout
            val okReceived = withTimeoutOrNull(10000L) {
                relay.messages.firstOrNull { msg ->
                    msg is RelayMessage.OkMsg && msg.eventId == eventId
                } as? RelayMessage.OkMsg
            }

            if (okReceived?.accepted == true) {
                Log.d(TAG, "Published event $eventId to $url")
                successfulRelays.add(url)
                // Record the publish
                dao.insertPublishedEvent(PublishedEventEntity(eventId, url))
            } else {
                Log.w(TAG, "Event $eventId not accepted by $url: ${okReceived?.message}")
            }
        }

        return successfulRelays
    }

    /**
     * Check if an event has been published to any relay.
     */
    suspend fun isPublished(eventId: String): Boolean {
        return dao.isEventPublished(eventId)
    }

    /**
     * Get the list of relays an event has been published to.
     */
    suspend fun getPublishedRelays(eventId: String): List<String> {
        return dao.getPublishedRelays(eventId)
    }
}
