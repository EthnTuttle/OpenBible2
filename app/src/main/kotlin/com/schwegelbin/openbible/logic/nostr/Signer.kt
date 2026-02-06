package com.schwegelbin.openbible.logic.nostr

import android.content.Context
import android.content.Intent

/**
 * Interface for signing Nostr events.
 * Implementations can use local key storage or delegate to an external signer app.
 */
interface Signer {
    val publicKeyHex: String
    suspend fun sign(event: UnsignedEvent): NostrEvent?
}

/**
 * Signs events using a locally stored private key.
 */
class LocalSigner(
    private val context: Context
) : Signer {

    override val publicKeyHex: String
        get() = getOrCreateKeypair(context).second

    private val privateKeyHex: String
        get() = getOrCreateKeypair(context).first

    override suspend fun sign(event: UnsignedEvent): NostrEvent {
        return event.sign(privateKeyHex)
    }
}

/**
 * Delegates signing to an external NIP-55 signer app (e.g., Amber).
 *
 * NIP-55 defines the Android Intent-based protocol:
 * - Action: "android.intent.action.VIEW"
 * - Package: signer app package (e.g., "com.greenart7c3.nostrsigner")
 * - Extras: "type" = "sign_event", "event" = unsigned event JSON
 * - Result: signed event JSON in the result intent
 *
 * NOTE: This requires Activity-level result handling. For now, this is a stub
 * that will be wired to an ActivityResultLauncher in the UI layer.
 */
class AmberSigner(
    private val context: Context,
    override val publicKeyHex: String
) : Signer {

    companion object {
        const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"
        const val INTENT_ACTION = "android.intent.action.VIEW"
        const val EXTRA_TYPE = "type"
        const val EXTRA_EVENT = "event"
        const val TYPE_SIGN_EVENT = "sign_event"
        const val TYPE_GET_PUBLIC_KEY = "get_public_key"
    }

    /**
     * Callback set by the UI layer to handle the signing Intent flow.
     * The UI launches the intent and returns the signed event.
     */
    var signCallback: (suspend (UnsignedEvent) -> NostrEvent?)? = null

    override suspend fun sign(event: UnsignedEvent): NostrEvent? {
        return signCallback?.invoke(event)
    }

    /**
     * Create an Intent to request the public key from Amber.
     */
    fun createGetPublicKeyIntent(): Intent {
        return Intent(INTENT_ACTION).apply {
            `package` = AMBER_PACKAGE
            putExtra(EXTRA_TYPE, TYPE_GET_PUBLIC_KEY)
        }
    }

    /**
     * Create an Intent to sign an event with Amber.
     */
    fun createSignEventIntent(event: UnsignedEvent): Intent {
        val eventJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("pubkey", kotlinx.serialization.json.JsonPrimitive(event.pubkey))
                put("created_at", kotlinx.serialization.json.JsonPrimitive(event.created_at))
                put("kind", kotlinx.serialization.json.JsonPrimitive(event.kind))
                put("tags", kotlinx.serialization.json.buildJsonArray {
                    for (tag in event.tags) {
                        add(kotlinx.serialization.json.buildJsonArray {
                            for (item in tag) {
                                add(kotlinx.serialization.json.JsonPrimitive(item))
                            }
                        })
                    }
                })
                put("content", kotlinx.serialization.json.JsonPrimitive(event.content))
            }
        )
        return Intent(INTENT_ACTION).apply {
            `package` = AMBER_PACKAGE
            putExtra(EXTRA_TYPE, TYPE_SIGN_EVENT)
            putExtra(EXTRA_EVENT, eventJson)
        }
    }

    /**
     * Check if Amber is installed on the device.
     */
    fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(AMBER_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
