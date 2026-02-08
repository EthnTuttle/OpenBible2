package com.schwegelbin.openbible.logic.nostr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

private const val PREFS_NAME = "nostr_keys"
private const val KEY_NSEC = "nsec"
private const val KEY_NPUB = "npub"

/**
 * Bech32 human-readable parts for Nostr keys.
 */
private const val NSEC_HRP = "nsec"
private const val NPUB_HRP = "npub"

/**
 * Get or create the encrypted SharedPreferences for key storage.
 */
private fun getEncryptedPrefs(context: Context) = EncryptedSharedPreferences.create(
    context,
    PREFS_NAME,
    MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

/**
 * Generate a new secp256k1 keypair.
 * Returns (privateKeyHex, publicKeyHex).
 */
fun generateKeypair(): Pair<String, String> {
    val secureRandom = SecureRandom()
    val privateKey = ByteArray(32)
    secureRandom.nextBytes(privateKey)

    val publicKey = Secp256k1.pubkeyCreate(privateKey)
    // x-only public key is the first 32 bytes of the compressed key
    val xOnlyPubkey = publicKey.copyOfRange(1, 33)

    return Pair(privateKey.toHexString(), xOnlyPubkey.toHexString())
}

/**
 * Get the stored keypair, or generate and store a new one.
 * Returns (privateKeyHex, publicKeyHex).
 */
fun getOrCreateKeypair(context: Context): Pair<String, String> {
    val prefs = getEncryptedPrefs(context)
    val existingNsec = prefs.getString(KEY_NSEC, null)
    val existingNpub = prefs.getString(KEY_NPUB, null)

    if (existingNsec != null && existingNpub != null) {
        return Pair(existingNsec, existingNpub)
    }

    val (nsec, npub) = generateKeypair()
    prefs.edit().putString(KEY_NSEC, nsec).putString(KEY_NPUB, npub).apply()
    return Pair(nsec, npub)
}

/**
 * Get the stored public key hex, or null if no keypair exists.
 */
fun getPublicKeyHex(context: Context): String? {
    return getEncryptedPrefs(context).getString(KEY_NPUB, null)
}

/**
 * Get the stored private key hex, or null if no keypair exists.
 */
fun getPrivateKeyHex(context: Context): String? {
    return getEncryptedPrefs(context).getString(KEY_NSEC, null)
}

/**
 * Store an externally provided keypair (e.g., imported nsec).
 */
fun storeKeypair(context: Context, privateKeyHex: String, publicKeyHex: String) {
    val prefs = getEncryptedPrefs(context)
    prefs.edit().putString(KEY_NSEC, privateKeyHex).putString(KEY_NPUB, publicKeyHex).apply()
}

/**
 * Store only a public key (for external signer like Amber).
 * The private key is managed by the external signer.
 */
fun storePublicKeyOnly(context: Context, publicKeyHex: String) {
    val prefs = getEncryptedPrefs(context)
    prefs.edit().remove(KEY_NSEC).putString(KEY_NPUB, publicKeyHex).apply()
}

/**
 * Check if using an external signer (has pubkey but no privkey).
 */
fun isUsingExternalSigner(context: Context): Boolean {
    val prefs = getEncryptedPrefs(context)
    return prefs.getString(KEY_NPUB, null) != null && prefs.getString(KEY_NSEC, null) == null
}

/**
 * Delete the stored keypair.
 */
fun deleteKeypair(context: Context) {
    val prefs = getEncryptedPrefs(context)
    prefs.edit().remove(KEY_NSEC).remove(KEY_NPUB).apply()
}

/**
 * Check if a keypair (or public key for external signer) is stored.
 */
fun hasKeypair(context: Context): Boolean {
    return getEncryptedPrefs(context).getString(KEY_NPUB, null) != null
}

// -- Hex utilities --

fun ByteArray.toHexString(): String {
    val hexChars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        hexChars[i * 2] = "0123456789abcdef"[v ushr 4]
        hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
    }
    return String(hexChars)
}

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        val high = Character.digit(this[i * 2], 16)
        val low = Character.digit(this[i * 2 + 1], 16)
        require(high != -1 && low != -1) { "Invalid hex character" }
        ((high shl 4) or low).toByte()
    }
}
