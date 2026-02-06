package com.schwegelbin.openbible.logic.nostr.embedded

import android.content.Context
import android.util.Log
import com.schwegelbin.openbible.logic.nostr.NostrEvent
import com.schwegelbin.openbible.logic.nostr.NostrFilter
import com.schwegelbin.openbible.logic.nostr.db.AppDatabase
import com.schwegelbin.openbible.logic.nostr.nostrEventFromJson
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private const val TAG = "EmbeddedRelay"
const val EMBEDDED_RELAY_PORT = 4870
const val EMBEDDED_RELAY_URL = "ws://127.0.0.1:$EMBEDDED_RELAY_PORT"

/**
 * A minimal in-process Nostr relay using Ktor CIO.
 * Supports NIP-01 (EVENT/REQ/CLOSE) and NIP-09 (deletion).
 * Backed by Room/SQLite via EventStore.
 */
class EmbeddedRelay(context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventStore = EventStore(AppDatabase.getInstance(context).nostrEventDao())
    private val subscriptionManager = SubscriptionManager()
    private val json = Json { ignoreUnknownKeys = true }

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /**
     * Start the embedded relay server.
     */
    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = EMBEDDED_RELAY_PORT, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/") {
                    val sessionId = UUID.randomUUID().toString()
                    Log.d(TAG, "Client connected: $sessionId")

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleMessage(sessionId, text) { response ->
                                    send(Frame.Text(response))
                                }
                            }
                        }
                    } finally {
                        subscriptionManager.removeAllForSession(sessionId)
                        Log.d(TAG, "Client disconnected: $sessionId")
                    }
                }
            }
        }

        scope.launch {
            try {
                server?.start(wait = false)
                Log.d(TAG, "Embedded relay started on $EMBEDDED_RELAY_URL")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start embedded relay", e)
            }
        }
    }

    /**
     * Stop the embedded relay server.
     */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.d(TAG, "Embedded relay stopped")
    }

    /**
     * Direct access to the event store for local operations
     * (bypasses WebSocket, used by HighlightRepository).
     */
    fun getEventStore(): EventStore = eventStore

    private suspend fun handleMessage(
        sessionId: String,
        text: String,
        send: suspend (String) -> Unit
    ) {
        try {
            val arr = json.parseToJsonElement(text).jsonArray
            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> handleEvent(arr, send)
                "REQ" -> handleReq(sessionId, arr, send)
                "CLOSE" -> handleClose(arr)
                else -> {
                    val notice = buildJsonArray {
                        add(JsonPrimitive("NOTICE"))
                        add(JsonPrimitive("Unsupported message type"))
                    }
                    send(json.encodeToString(JsonArray.serializer(), notice))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    private suspend fun handleEvent(
        arr: JsonArray,
        send: suspend (String) -> Unit
    ) {
        val event = nostrEventFromJson(arr[1].toString()) ?: run {
            sendOk(send, "", false, "invalid: could not parse event")
            return
        }

        // Handle NIP-09 deletion events
        if (event.kind == 5) {
            eventStore.processDeletion(event)
            sendOk(send, event.id, true, "")
            return
        }

        val stored = eventStore.storeEvent(event)
        if (stored) {
            sendOk(send, event.id, true, "")
            // Push to matching subscriptions would happen here
            // For simplicity, subscribers re-query via REQ
        } else {
            sendOk(send, event.id, true, "duplicate:")
        }
    }

    private suspend fun handleReq(
        sessionId: String,
        arr: JsonArray,
        send: suspend (String) -> Unit
    ) {
        val subId = arr[1].jsonPrimitive.content
        val filterJson = arr[2]

        val filter = parseFilter(filterJson.toString())
        subscriptionManager.addSubscription(subId, filter, sessionId)

        // Query stored events and send them
        val events = eventStore.queryEvents(filter)
        for (event in events) {
            val msg = buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(JsonPrimitive(subId))
                add(json.parseToJsonElement(eventToJson(event)))
            }
            send(json.encodeToString(JsonArray.serializer(), msg))
        }

        // Send EOSE
        val eose = buildJsonArray {
            add(JsonPrimitive("EOSE"))
            add(JsonPrimitive(subId))
        }
        send(json.encodeToString(JsonArray.serializer(), eose))
    }

    private fun handleClose(arr: JsonArray) {
        val subId = arr[1].jsonPrimitive.content
        subscriptionManager.removeSubscription(subId)
    }

    private suspend fun sendOk(
        send: suspend (String) -> Unit,
        eventId: String,
        accepted: Boolean,
        message: String
    ) {
        val ok = buildJsonArray {
            add(JsonPrimitive("OK"))
            add(JsonPrimitive(eventId))
            add(JsonPrimitive(accepted))
            add(JsonPrimitive(message))
        }
        send(json.encodeToString(JsonArray.serializer(), ok))
    }

    private fun parseFilter(filterStr: String): NostrFilter {
        return try {
            val obj = json.parseToJsonElement(filterStr)
            val jsonObj = obj as? kotlinx.serialization.json.JsonObject ?: return NostrFilter()

            val ids = jsonObj["ids"]?.jsonArray?.map { it.jsonPrimitive.content }
            val authors = jsonObj["authors"]?.jsonArray?.map { it.jsonPrimitive.content }
            val kinds = jsonObj["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }
            val since = jsonObj["since"]?.jsonPrimitive?.intOrNull?.toLong()
            val until = jsonObj["until"]?.jsonPrimitive?.intOrNull?.toLong()
            val limit = jsonObj["limit"]?.jsonPrimitive?.intOrNull

            val tags = mutableMapOf<String, List<String>>()
            jsonObj.entries.forEach { (key, value) ->
                if (key.startsWith("#") && key.length > 1) {
                    val tagName = key.substring(1)
                    tags[tagName] = value.jsonArray.map { it.jsonPrimitive.content }
                }
            }

            NostrFilter(
                ids = ids,
                authors = authors,
                kinds = kinds,
                tags = if (tags.isNotEmpty()) tags else null,
                since = since,
                until = until,
                limit = limit
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse filter: $filterStr", e)
            NostrFilter()
        }
    }
}

private fun eventToJson(event: NostrEvent): String {
    return kotlinx.serialization.json.Json.encodeToString(NostrEvent.serializer(), event)
}
