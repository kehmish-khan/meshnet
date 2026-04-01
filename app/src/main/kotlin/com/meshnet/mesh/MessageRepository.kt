package com.meshnet.mesh

import android.util.Log
import com.meshnet.crypto.CryptoManager
import com.meshnet.db.MeshDatabase
import com.meshnet.grpc.MeshGrpcServiceImpl
import com.meshnet.model.Contact
import com.meshnet.model.Message
import com.meshnet.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "MessageRepository"

/**
 * MessageRepository
 *
 * Single source of truth for the UI layer.
 * Handles:
 *  - Sending messages (encrypt → store → route)
 *  - Receiving messages (decrypt → store → notify UI)
 *  - Contact management
 *  - Conversation history flows (reactive via Room)
 */
class MessageRepository(
    private val db: MeshDatabase,
    private val crypto: CryptoManager,
    private val grpcService: MeshGrpcServiceImpl,
    private val router: MeshRouter
) {

    // ─── Contacts ────────────────────────────────────────────────────────────

    fun getContacts(): Flow<List<Contact>> = db.contactDao().getAllContacts()

    suspend fun addContact(contact: Contact) {
        withContext(Dispatchers.IO) {
            db.contactDao().insertContact(contact)
            Log.d(TAG, "Contact added: ${contact.displayName}")
        }
    }

    suspend fun getContactByKey(key: String): Contact? =
        withContext(Dispatchers.IO) { db.contactDao().getContactByKey(key) }

    // ─── Messages ────────────────────────────────────────────────────────────

    /**
     * Get all messages in a conversation as a reactive Flow.
     * UI observes this and auto-updates when new messages arrive.
     */
    fun getConversation(peerKey: String): Flow<List<Message>> =
        db.messageDao().getConversation(crypto.getPublicKey(), peerKey)

    /**
     * Send a message to a contact.
     * Encrypts, signs, stores locally, then routes into mesh.
     */
    suspend fun sendMessage(toKey: String, plaintext: String): Boolean {
        return withContext(Dispatchers.IO) {
            var success = false
            grpcService.sendNewMessage(toKey, plaintext) { result ->
                success = result
            }
            // Small delay to let coroutine inside complete
            kotlinx.coroutines.delay(100)
            success
        }
    }

    /**
     * Called by router when a message arrives for THIS device.
     * Decrypts and stores the plaintext.
     */
    suspend fun onMessageReceived(message: Message) {
        withContext(Dispatchers.IO) {
            try {
                // Check we haven't already processed this message
                if (db.messageDao().messageExists(message.messageId) > 0) {
                    Log.d(TAG, "Duplicate message ignored: ${message.messageId}")
                    return@withContext
                }

                // Decrypt the ciphertext
                val plaintext = if (message.ciphertext.isNotEmpty()) {
                    try {
                        crypto.decrypt(
                            com.meshnet.crypto.EncryptedPayload(
                                ciphertext = message.ciphertext,
                                nonce = message.nonce
                            ),
                            message.fromKey
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Decryption failed for ${message.messageId}: ${e.message}")
                        "[Could not decrypt message]"
                    }
                } else {
                    message.plaintext
                }

                val stored = message.copy(
                    plaintext = plaintext,
                    status = MessageStatus.DELIVERED,
                    isIncoming = true
                )
                db.messageDao().insertMessage(stored)

                // Update contact last seen
                db.contactDao().updateLastSeen(message.fromKey, System.currentTimeMillis())

                Log.d(TAG, "Message decrypted and stored: ${message.messageId}")

            } catch (e: Exception) {
                Log.e(TAG, "Error processing received message: ${e.message}")
            }
        }
    }

    // ─── Identity ────────────────────────────────────────────────────────────

    fun getMyPublicKey() = crypto.getPublicKey()
    fun getMyUsername() = crypto.getUsername()
    fun getMyFingerprint() = crypto.getFingerprint()

    // ─── Stats ───────────────────────────────────────────────────────────────

    fun getActivePeers() = router.getActivePeers()
    fun getRouteCount() = router.getRouteCount()
    fun getPendingCount() = router.getPendingCount()
}
