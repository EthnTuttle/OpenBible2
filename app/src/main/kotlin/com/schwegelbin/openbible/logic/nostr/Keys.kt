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

// -- Bech32 encoding for npub/nsec --

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

/**
 * Convert a hex public key to npub format (bech32).
 */
fun hexToNpub(hex: String): String {
    return bech32Encode("npub", hex.hexToByteArray())
}

/**
 * Convert a hex private key to nsec format (bech32).
 */
fun hexToNsec(hex: String): String {
    return bech32Encode("nsec", hex.hexToByteArray())
}

/**
 * Decode an npub to hex public key.
 * Returns null if invalid.
 */
fun npubToHex(npub: String): String? {
    return try {
        val (hrp, data) = bech32Decode(npub)
        if (hrp != "npub") return null
        data.toHexString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Decode an nsec to hex private key.
 * Returns null if invalid.
 */
fun nsecToHex(nsec: String): String? {
    return try {
        val (hrp, data) = bech32Decode(nsec)
        if (hrp != "nsec") return null
        data.toHexString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Bech32 encode data with a human-readable prefix.
 */
private fun bech32Encode(hrp: String, data: ByteArray): String {
    val converted = convertBits(data, 8, 5, true)
    val checksum = createChecksum(hrp, converted)
    val combined = converted + checksum
    val result = StringBuilder(hrp.length + 1 + combined.size)
    result.append(hrp)
    result.append('1')
    for (b in combined) {
        result.append(BECH32_CHARSET[b.toInt()])
    }
    return result.toString()
}

/**
 * Bech32 decode a string to hrp and data.
 */
private fun bech32Decode(str: String): Pair<String, ByteArray> {
    val lower = str.lowercase()
    val pos = lower.lastIndexOf('1')
    require(pos >= 1 && pos + 7 <= lower.length) { "Invalid bech32 string" }

    val hrp = lower.substring(0, pos)
    val dataStr = lower.substring(pos + 1)

    val data = ByteArray(dataStr.length)
    for (i in dataStr.indices) {
        val c = BECH32_CHARSET.indexOf(dataStr[i])
        require(c != -1) { "Invalid bech32 character" }
        data[i] = c.toByte()
    }

    require(verifyChecksum(hrp, data)) { "Invalid bech32 checksum" }

    val converted = convertBits(data.copyOfRange(0, data.size - 6), 5, 8, false)
    return Pair(hrp, converted)
}

private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
    var acc = 0
    var bits = 0
    val result = mutableListOf<Byte>()
    val maxv = (1 shl toBits) - 1

    for (b in data) {
        val value = b.toInt() and 0xFF
        acc = (acc shl fromBits) or value
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            result.add(((acc shr bits) and maxv).toByte())
        }
    }

    if (pad && bits > 0) {
        result.add(((acc shl (toBits - bits)) and maxv).toByte())
    }

    return result.toByteArray()
}

private fun polymod(values: ByteArray): Int {
    val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    var chk = 1
    for (v in values) {
        val top = chk shr 25
        chk = ((chk and 0x1ffffff) shl 5) xor (v.toInt() and 0xFF)
        for (i in 0..4) {
            if ((top shr i) and 1 == 1) {
                chk = chk xor generator[i]
            }
        }
    }
    return chk
}

private fun hrpExpand(hrp: String): ByteArray {
    val result = ByteArray(hrp.length * 2 + 1)
    for (i in hrp.indices) {
        result[i] = (hrp[i].code shr 5).toByte()
        result[hrp.length + 1 + i] = (hrp[i].code and 31).toByte()
    }
    result[hrp.length] = 0
    return result
}

private fun createChecksum(hrp: String, data: ByteArray): ByteArray {
    val values = hrpExpand(hrp) + data + byteArrayOf(0, 0, 0, 0, 0, 0)
    val polymod = polymod(values) xor 1
    val result = ByteArray(6)
    for (i in 0..5) {
        result[i] = ((polymod shr (5 * (5 - i))) and 31).toByte()
    }
    return result
}

private fun verifyChecksum(hrp: String, data: ByteArray): Boolean {
    return polymod(hrpExpand(hrp) + data) == 1
}

// -- NIP-19 TLV encoding for nevent/naddr --

/**
 * TLV types for NIP-19 shareable identifiers.
 */
private const val TLV_SPECIAL = 0      // event id for nevent, identifier for naddr
private const val TLV_RELAY = 1        // relay URL
private const val TLV_AUTHOR = 2       // pubkey of author
private const val TLV_KIND = 3         // event kind

/**
 * Encode an event ID (and optional relay hints) as a nevent bech32 string.
 * This is a shareable identifier that other Nostr clients can use to fetch the event.
 */
fun encodeNevent(
    eventIdHex: String,
    relays: List<String> = emptyList(),
    authorPubkeyHex: String? = null,
    kind: Int? = null
): String {
    val tlvData = mutableListOf<Byte>()
    
    // Add event ID (type 0)
    val eventIdBytes = eventIdHex.hexToByteArray()
    tlvData.add(TLV_SPECIAL.toByte())
    tlvData.add(eventIdBytes.size.toByte())
    tlvData.addAll(eventIdBytes.toList())
    
    // Add relay hints (type 1)
    for (relay in relays.take(3)) { // Limit to 3 relays
        val relayBytes = relay.toByteArray(Charsets.UTF_8)
        tlvData.add(TLV_RELAY.toByte())
        tlvData.add(relayBytes.size.toByte())
        tlvData.addAll(relayBytes.toList())
    }
    
    // Add author pubkey (type 2)
    if (authorPubkeyHex != null) {
        val authorBytes = authorPubkeyHex.hexToByteArray()
        tlvData.add(TLV_AUTHOR.toByte())
        tlvData.add(authorBytes.size.toByte())
        tlvData.addAll(authorBytes.toList())
    }
    
    // Add kind (type 3)
    if (kind != null) {
        tlvData.add(TLV_KIND.toByte())
        tlvData.add(4.toByte()) // kind is 4 bytes (32-bit big-endian)
        tlvData.add((kind shr 24).toByte())
        tlvData.add((kind shr 16).toByte())
        tlvData.add((kind shr 8).toByte())
        tlvData.add(kind.toByte())
    }
    
    return bech32Encode("nevent", tlvData.toByteArray())
}
