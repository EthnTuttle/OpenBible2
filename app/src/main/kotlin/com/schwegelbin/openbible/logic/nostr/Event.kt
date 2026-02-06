package com.schwegelbin.openbible.logic.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

/**
 * A signed Nostr event (NIP-01).
 */
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)

/**
 * An unsigned event, ready to be signed.
 */
data class UnsignedEvent(
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String
)

/**
 * Compute the event ID as defined in NIP-01:
 * SHA-256 of the serialized event array [0, pubkey, created_at, kind, tags, content].
 */
fun UnsignedEvent.computeId(): String {
    val serialized = buildJsonArray {
        add(JsonPrimitive(0))
        add(JsonPrimitive(pubkey))
        add(JsonPrimitive(created_at))
        add(JsonPrimitive(kind))
        add(buildJsonArray {
            for (tag in tags) {
                add(buildJsonArray {
                    for (item in tag) {
                        add(JsonPrimitive(item))
                    }
                })
            }
        })
        add(JsonPrimitive(content))
    }

    val jsonBytes = Json.encodeToString(serialized).toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(jsonBytes)
    return digest.toHexString()
}

/**
 * Sign an unsigned event with a private key (schnorr signature per NIP-01).
 * Returns a fully signed NostrEvent.
 */
fun UnsignedEvent.sign(privateKeyHex: String): NostrEvent {
    val eventId = computeId()
    val idBytes = eventId.hexToByteArray()
    val privKeyBytes = privateKeyHex.hexToByteArray()

    // Aux random data for schnorr signing
    val auxRand = ByteArray(32)
    java.security.SecureRandom().nextBytes(auxRand)

    val sig = Secp256k1.signSchnorr(idBytes, privKeyBytes, auxRand)

    return NostrEvent(
        id = eventId,
        pubkey = pubkey,
        created_at = created_at,
        kind = kind,
        tags = tags,
        content = content,
        sig = sig.toHexString()
    )
}

/**
 * Verify the schnorr signature of a signed event.
 */
fun NostrEvent.verify(): Boolean {
    return try {
        val idBytes = id.hexToByteArray()
        val sigBytes = sig.hexToByteArray()
        val pubkeyBytes = pubkey.hexToByteArray()
        Secp256k1.verifySchnorr(sigBytes, idBytes, pubkeyBytes)
    } catch (_: Exception) {
        false
    }
}

/**
 * Create an unsigned event with the current timestamp.
 */
fun createUnsignedEvent(
    pubkey: String,
    kind: Int,
    tags: List<List<String>>,
    content: String
): UnsignedEvent {
    return UnsignedEvent(
        pubkey = pubkey,
        created_at = System.currentTimeMillis() / 1000,
        kind = kind,
        tags = tags,
        content = content
    )
}

/**
 * Create a NIP-09 deletion event for the given event IDs.
 */
fun createDeletionEvent(
    pubkey: String,
    eventIds: List<String>,
    reason: String = ""
): UnsignedEvent {
    val tags = eventIds.map { listOf("e", it) }
    return createUnsignedEvent(
        pubkey = pubkey,
        kind = 5,
        tags = tags,
        content = reason
    )
}

/**
 * Serialize a NostrEvent to its JSON representation.
 */
fun NostrEvent.toJson(): String = Json.encodeToString(this)

/**
 * Deserialize a JSON string to a NostrEvent.
 */
fun nostrEventFromJson(json: String): NostrEvent? {
    return try {
        Json { ignoreUnknownKeys = true }.decodeFromString<NostrEvent>(json)
    } catch (_: Exception) {
        null
    }
}
