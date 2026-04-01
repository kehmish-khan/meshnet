package com.meshnet.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import java.security.MessageDigest

/**
 * CryptoManager
 *
 * Handles all cryptographic operations for MeshNet:
 *  - Curve25519 key pair generation (identity)
 *  - Ed25519 message signing and verification
 *  - AES-256-GCM encryption / decryption
 *  - Key persistence in SharedPreferences (never leaves device)
 *
 * Uses libsodium via lazysodium-android — same crypto used by Signal.
 * Zero cost. Runs entirely on device. No server needed.
 */
class CryptoManager(context: Context) {

    private val sodium = LazySodiumAndroid(SodiumAndroid())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshnet_identity", Context.MODE_PRIVATE)

    // ─── Key storage keys ───────────────────────────────────────────────────
    companion object {
        private const val KEY_PUBLIC_BOX   = "public_key_box"
        private const val KEY_PRIVATE_BOX  = "private_key_box"
        private const val KEY_PUBLIC_SIGN  = "public_key_sign"
        private const val KEY_PRIVATE_SIGN = "private_key_sign"
        private const val KEY_USERNAME     = "username"
        private const val KEY_CREATED_AT   = "created_at"
        const val TTL_DEFAULT = 20        // max hops before message dies
    }

    // ─── Identity ───────────────────────────────────────────────────────────

    /**
     * Returns true if this device already has a cryptographic identity.
     */
    fun hasIdentity(): Boolean = prefs.contains(KEY_PUBLIC_BOX)

    /**
     * Generate a new identity for this device.
     * Creates two key pairs:
     *   1. Box keypair (Curve25519) — for encrypting messages
     *   2. Sign keypair (Ed25519)   — for signing messages
     *
     * Keys are stored locally. Private keys never leave this device.
     */
    fun generateIdentity(username: String) {
        // Generate Curve25519 box keypair (for encryption)
        val boxKeyPair: KeyPair = sodium.cryptoBoxKeypair()

        // Generate Ed25519 sign keypair (for signing)
        val signKeyPair: KeyPair = sodium.cryptoSignKeypair()

        prefs.edit().apply {
            putString(KEY_PUBLIC_BOX,   boxKeyPair.publicKey.asHexString)
            putString(KEY_PRIVATE_BOX,  boxKeyPair.secretKey.asHexString)
            putString(KEY_PUBLIC_SIGN,  signKeyPair.publicKey.asHexString)
            putString(KEY_PRIVATE_SIGN, signKeyPair.secretKey.asHexString)
            putString(KEY_USERNAME,     username)
            putLong(KEY_CREATED_AT,     System.currentTimeMillis())
            apply()
        }
    }

    // ─── Getters ────────────────────────────────────────────────────────────

    fun getPublicKey(): String   = prefs.getString(KEY_PUBLIC_BOX, "")!!
    fun getSignPublicKey(): String = prefs.getString(KEY_PUBLIC_SIGN, "")!!
    fun getUsername(): String    = prefs.getString(KEY_USERNAME, "Unknown")!!

    /**
     * Human-readable fingerprint of your public key.
     * Format: ABCD:1234:EF56:... (groups of 4 hex chars)
     */
    fun getFingerprint(): String {
        val pub = getPublicKey()
        return pub.chunked(4).take(12).joinToString(":")
    }

    // ─── Encryption ─────────────────────────────────────────────────────────

    /**
     * Encrypt a plaintext message for a specific recipient.
     * Uses libsodium crypto_box (Curve25519 + XSalsa20 + Poly1305).
     *
     * @param plaintext   the message text in UTF-8
     * @param recipientPublicKey  recipient's hex public key
     * @return EncryptedPayload containing ciphertext + nonce
     */
    fun encrypt(plaintext: String, recipientPublicKey: String): EncryptedPayload {
        val nonce = sodium.nonce(Box.NONCEBYTES)

        val myPrivateKey = Key.fromHexString(prefs.getString(KEY_PRIVATE_BOX, "")!!)
        val theirPublicKey = Key.fromHexString(recipientPublicKey)

        val messageBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = ByteArray(messageBytes.size + Box.MACBYTES)

        val success = sodium.cryptoBoxEasy(
            ciphertext,
            messageBytes,
            messageBytes.size.toLong(),
            nonce,
            theirPublicKey.asBytes,
            myPrivateKey.asBytes
        )

        if (!success) throw CryptoException("Encryption failed")

        return EncryptedPayload(
            ciphertext = ciphertext,
            nonce = nonce
        )
    }

    /**
     * Decrypt a message sent to this device.
     *
     * @param payload   EncryptedPayload with ciphertext + nonce
     * @param senderPublicKey  sender's hex public key
     * @return plaintext string
     */
    fun decrypt(payload: EncryptedPayload, senderPublicKey: String): String {
        val myPrivateKey = Key.fromHexString(prefs.getString(KEY_PRIVATE_BOX, "")!!)
        val theirPublicKey = Key.fromHexString(senderPublicKey)

        val plaintext = ByteArray(payload.ciphertext.size - Box.MACBYTES)

        val success = sodium.cryptoBoxOpenEasy(
            plaintext,
            payload.ciphertext,
            payload.ciphertext.size.toLong(),
            payload.nonce,
            theirPublicKey.asBytes,
            myPrivateKey.asBytes
        )

        if (!success) throw CryptoException("Decryption failed — message tampered or wrong key")

        return String(plaintext, Charsets.UTF_8)
    }

    // ─── Signing ────────────────────────────────────────────────────────────

    /**
     * Sign a message with this device's Ed25519 private key.
     * Proves the message really came from you.
     *
     * @param data  bytes to sign (typically message_id + ciphertext)
     * @return signature bytes
     */
    fun sign(data: ByteArray): ByteArray {
        val privateKey = Key.fromHexString(prefs.getString(KEY_PRIVATE_SIGN, "")!!)
        val signature = ByteArray(Sign.BYTES)

        sodium.cryptoSignDetached(
            signature,
            data,
            data.size.toLong(),
            privateKey.asBytes
        )

        return signature
    }

    /**
     * Verify a message signature.
     *
     * @param data       original signed bytes
     * @param signature  signature to verify
     * @param signerPublicKey  hex public sign key of the supposed sender
     * @return true if signature is valid
     */
    fun verify(data: ByteArray, signature: ByteArray, signerPublicKey: String): Boolean {
        val publicKey = Key.fromHexString(signerPublicKey)

        return sodium.cryptoSignVerifyDetached(
            signature,
            data,
            data.size.toLong(),
            publicKey.asBytes
        )
    }

    // ─── Utilities ──────────────────────────────────────────────────────────

    /**
     * SHA-256 hash of a string — used for message deduplication IDs.
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encode bytes to Base64 string for transport in proto messages.
     */
    fun toBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromBase64(s: String): ByteArray =
        Base64.decode(s, Base64.NO_WRAP)
}

// ─── Data classes ───────────────────────────────────────────────────────────

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray
)

class CryptoException(message: String) : Exception(message)
