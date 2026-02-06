package com.schwegelbin.openbible.logic.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val TAG = "RelayPool"

/**
 * Manages connections to multiple Nostr relays and routes messages.
 */
class RelayPool(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val relays = mutableMapOf<String, Relay>()

    private val _messages = MutableSharedFlow<Pair<String, RelayMessage>>(extraBufferCapacity = 256)
    val messages: Flow<Pair<String, RelayMessage>> = _messages.asSharedFlow()

    /**
     * Add a relay to the pool and start collecting its messages.
     */
    fun addRelay(url: String): Relay {
        val existing = relays[url]
        if (existing != null) return existing

        val relay = Relay(url, scope)
        relays[url] = relay

        scope.launch {
            relay.messages.collect { message ->
                _messages.emit(Pair(url, message))
            }
        }

        return relay
    }

    /**
     * Remove a relay from the pool and disconnect it.
     */
    fun removeRelay(url: String) {
        relays.remove(url)?.disconnect()
    }

    /**
     * Get a relay by URL.
     */
    fun getRelay(url: String): Relay? = relays[url]

    /**
     * Connect all relays in the pool.
     */
    fun connectAll() {
        relays.values.forEach { it.connect() }
    }

    /**
     * Disconnect all relays in the pool.
     */
    fun disconnectAll() {
        relays.values.forEach { it.disconnect() }
    }

    /**
     * Send an event to all connected relays.
     */
    fun broadcastEvent(event: NostrEvent) {
        relays.values.forEach { relay ->
            if (relay.currentState == RelayState.CONNECTED) {
                relay.sendEvent(event)
            }
        }
    }

    /**
     * Send an event to specific relays.
     */
    fun sendEventTo(event: NostrEvent, urls: List<String>) {
        urls.forEach { url ->
            relays[url]?.let { relay ->
                if (relay.currentState == RelayState.CONNECTED) {
                    relay.sendEvent(event)
                } else {
                    Log.w(TAG, "Relay $url not connected, cannot send event")
                }
            }
        }
    }

    /**
     * Get all relay URLs in the pool.
     */
    fun getRelayUrls(): Set<String> = relays.keys.toSet()

    /**
     * Get all connected relay URLs.
     */
    fun getConnectedRelayUrls(): Set<String> = relays
        .filter { it.value.currentState == RelayState.CONNECTED }
        .keys

    /**
     * Check if any relay is connected.
     */
    fun hasConnectedRelay(): Boolean = relays.values.any {
        it.currentState == RelayState.CONNECTED
    }
}
