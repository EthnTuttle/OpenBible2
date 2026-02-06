package com.schwegelbin.openbible.logic.nostr.sync

import android.util.Log
import com.schwegelbin.openbible.logic.nostr.NostrFilter
import com.schwegelbin.openbible.logic.nostr.Relay
import com.schwegelbin.openbible.logic.nostr.RelayMessage
import com.schwegelbin.openbible.logic.nostr.embedded.EventStore
import com.schwegelbin.openbible.logic.nostr.hexToByteArray
import com.schwegelbin.openbible.logic.nostr.toHexString
import com.vitorpamplona.negentropy.Negentropy
import com.vitorpamplona.negentropy.storage.StorageVector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "NegentropySync"
private const val FRAME_SIZE_LIMIT = 50_000L
private const val SYNC_TIMEOUT_MS = 30_000L

/**
 * Result of a Negentropy sync operation.
 */
data class SyncResult(
    val haveIds: Set<String>,  // IDs we have that the remote needs
    val needIds: Set<String>,  // IDs the remote has that we need
    val success: Boolean,
    val error: String? = null
)

/**
 * Performs Negentropy set reconciliation between the local EventStore
 * and a remote relay to efficiently determine which events need to be
 * transferred in each direction.
 */
class NegentropySync(
    private val eventStore: EventStore,
    private val relay: Relay
) {
    /**
     * Perform a full sync for events matching the given filter.
     */
    suspend fun reconcile(filter: NostrFilter): SyncResult {
        val haveIds = mutableSetOf<String>()
        val needIds = mutableSetOf<String>()

        try {
            // 1. Build local storage vector
            val kind = filter.kinds?.firstOrNull() ?: return SyncResult(
                haveIds, needIds, false, "Filter must specify a kind"
            )
            val localEvents = eventStore.getEventIdsAndTimestamps(kind)

            val storage = StorageVector()
            localEvents.forEach { event ->
                storage.insert(event.created_at, event.id)
            }
            storage.seal()

            // 2. Initiate Negentropy
            val ne = Negentropy(storage, FRAME_SIZE_LIMIT)
            val initMsg = ne.initiate()

            // 3. Send NEG-OPEN (hex-encode the binary message for transport)
            val subId = relay.nextNegSubId()
            relay.sendNegOpen(subId, filter, initMsg.toHexString())

            // 4. Exchange rounds until reconciliation is complete
            while (true) {
                val response = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                    waitForNegResponse(subId)
                } ?: return SyncResult(haveIds, needIds, false, "Sync timed out")

                if (response is RelayMessage.NegErr) {
                    Log.w(TAG, "NEG-ERR from ${relay.url}: ${response.reason}")
                    return SyncResult(haveIds, needIds, false, response.reason)
                }

                if (response !is RelayMessage.NegMsg) {
                    return SyncResult(haveIds, needIds, false, "Unexpected message type")
                }

                // Reconcile (decode hex message from relay)
                val responseBytes = response.message.hexToByteArray()
                val result = ne.reconcile(responseBytes)

                // Extract IDs from the result
                result?.sendIds?.forEach { id ->
                    haveIds.add(id.toHexString())
                }
                result?.needIds?.forEach { id ->
                    needIds.add(id.toHexString())
                }

                val nextMsg = result?.msg
                if (nextMsg == null) {
                    break
                }

                // Send next round (hex-encode)
                relay.sendNegMsg(subId, nextMsg.toHexString())
            }

            // 5. Close the Negentropy session
            relay.sendNegClose(subId)

            Log.d(TAG, "Sync with ${relay.url}: have=${haveIds.size}, need=${needIds.size}")
            return SyncResult(haveIds, needIds, true)

        } catch (e: Exception) {
            Log.e(TAG, "Negentropy sync failed with ${relay.url}", e)
            return SyncResult(haveIds, needIds, false, e.message)
        }
    }

    private suspend fun waitForNegResponse(subId: String): RelayMessage {
        return relay.messages.first { msg ->
            when (msg) {
                is RelayMessage.NegMsg -> msg.subscriptionId == subId
                is RelayMessage.NegErr -> msg.subscriptionId == subId
                else -> false
            }
        }
    }
}
