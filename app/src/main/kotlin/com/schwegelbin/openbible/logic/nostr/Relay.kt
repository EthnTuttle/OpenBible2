package com.schwegelbin.openbible.logic.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "NostrRelay"

/**
 * NIP-01 filter for querying events from a relay.
 */
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null
)

/**
 * Serialize a NostrFilter to its JSON representation for REQ messages.
 */
fun NostrFilter.toJson(): String {
    return Json.encodeToString(buildJsonObject {
        ids?.let { put("ids", buildJsonArray { it.forEach { add(JsonPrimitive(it)) } }) }
        authors?.let { put("authors", buildJsonArray { it.forEach { add(JsonPrimitive(it)) } }) }
        kinds?.let { put("kinds", buildJsonArray { it.forEach { add(JsonPrimitive(it)) } }) }
        tags?.forEach { (key, values) ->
            put("#$key", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
        since?.let { put("since", JsonPrimitive(it)) }
        until?.let { put("until", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
    })
}

/**
 * Messages received from a relay.
 */
sealed class RelayMessage {
    data class EventMsg(val subscriptionId: String, val event: NostrEvent) : RelayMessage()
    data class OkMsg(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage()
    data class EoseMsg(val subscriptionId: String) : RelayMessage()
    data class NoticeMsg(val message: String) : RelayMessage()
    data class NegMsg(val subscriptionId: String, val message: String) : RelayMessage()
    data class NegErr(val subscriptionId: String, val reason: String) : RelayMessage()
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage()
}

/**
 * Connection state of a relay.
 */
enum class RelayState {
    DISCONNECTED, CONNECTING, CONNECTED
}

/**
 * A WebSocket client for a single Nostr relay.
 */
class Relay(
    val url: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 256)
    val messages: Flow<RelayMessage> = _messages.asSharedFlow()

    private val _state = MutableSharedFlow<RelayState>(replay = 1, extraBufferCapacity = 4)
    val state: Flow<RelayState> = _state.asSharedFlow()

    var currentState: RelayState = RelayState.DISCONNECTED
        private set

    private var subscriptionCounter = 0

    fun connect() {
        if (currentState == RelayState.CONNECTED || currentState == RelayState.CONNECTING) return

        currentState = RelayState.CONNECTING
        scope.launch { _state.emit(RelayState.CONNECTING) }

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentState = RelayState.CONNECTED
                scope.launch { _state.emit(RelayState.CONNECTED) }
                Log.d(TAG, "Connected to $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    parseMessage(text)?.let { _messages.emit(it) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                currentState = RelayState.DISCONNECTED
                scope.launch { _state.emit(RelayState.DISCONNECTED) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure for $url: ${t.message}")
                currentState = RelayState.DISCONNECTED
                scope.launch { _state.emit(RelayState.DISCONNECTED) }
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        currentState = RelayState.DISCONNECTED
        scope.launch { _state.emit(RelayState.DISCONNECTED) }
    }

    /**
     * Send a raw JSON message to the relay.
     */
    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    /**
     * Send an EVENT message to publish an event.
     */
    fun sendEvent(event: NostrEvent): Boolean {
        val msg = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(Json.parseToJsonElement(event.toJson()))
        }
        return send(Json.encodeToString(msg))
    }

    /**
     * Send a REQ message to subscribe to events matching a filter.
     * Returns the subscription ID.
     */
    fun sendReq(filter: NostrFilter): String {
        val subId = "sub_${++subscriptionCounter}"
        val msg = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(Json.parseToJsonElement(filter.toJson()))
        }
        send(Json.encodeToString(msg))
        return subId
    }

    /**
     * Send a CLOSE message to unsubscribe.
     */
    fun sendClose(subscriptionId: String): Boolean {
        val msg = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }
        return send(Json.encodeToString(msg))
    }

    /**
     * Send a NEG-OPEN message for Negentropy sync (NIP-77).
     */
    fun sendNegOpen(subscriptionId: String, filter: NostrFilter, initialMessage: String): Boolean {
        val msg = buildJsonArray {
            add(JsonPrimitive("NEG-OPEN"))
            add(JsonPrimitive(subscriptionId))
            add(Json.parseToJsonElement(filter.toJson()))
            add(JsonPrimitive(initialMessage))
        }
        return send(Json.encodeToString(msg))
    }

    /**
     * Send a NEG-MSG for continued Negentropy reconciliation.
     */
    fun sendNegMsg(subscriptionId: String, message: String): Boolean {
        val msg = buildJsonArray {
            add(JsonPrimitive("NEG-MSG"))
            add(JsonPrimitive(subscriptionId))
            add(JsonPrimitive(message))
        }
        return send(Json.encodeToString(msg))
    }

    /**
     * Send a NEG-CLOSE to end a Negentropy session.
     */
    fun sendNegClose(subscriptionId: String): Boolean {
        val msg = buildJsonArray {
            add(JsonPrimitive("NEG-CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }
        return send(Json.encodeToString(msg))
    }

    /**
     * Generate a unique subscription ID for Negentropy.
     */
    fun nextNegSubId(): String = "neg_${++subscriptionCounter}"

    private fun parseMessage(text: String): RelayMessage? {
        return try {
            val json = Json.parseToJsonElement(text).jsonArray
            when (json[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val subId = json[1].jsonPrimitive.content
                    val event = Json { ignoreUnknownKeys = true }
                        .decodeFromString<NostrEvent>(json[2].toString())
                    RelayMessage.EventMsg(subId, event)
                }
                "OK" -> {
                    val eventId = json[1].jsonPrimitive.content
                    val accepted = json[2].jsonPrimitive.content.toBoolean()
                    val msg = if (json.size > 3) json[3].jsonPrimitive.content else ""
                    RelayMessage.OkMsg(eventId, accepted, msg)
                }
                "EOSE" -> {
                    RelayMessage.EoseMsg(json[1].jsonPrimitive.content)
                }
                "NOTICE" -> {
                    RelayMessage.NoticeMsg(json[1].jsonPrimitive.content)
                }
                "CLOSED" -> {
                    val subId = json[1].jsonPrimitive.content
                    val msg = json[2].jsonPrimitive.content
                    RelayMessage.Closed(subId, msg)
                }
                "NEG-MSG" -> {
                    val subId = json[1].jsonPrimitive.content
                    val msg = json[2].jsonPrimitive.content
                    RelayMessage.NegMsg(subId, msg)
                }
                "NEG-ERR" -> {
                    val subId = json[1].jsonPrimitive.content
                    val reason = json[2].jsonPrimitive.content
                    RelayMessage.NegErr(subId, reason)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse relay message: $text", e)
            null
        }
    }
}
