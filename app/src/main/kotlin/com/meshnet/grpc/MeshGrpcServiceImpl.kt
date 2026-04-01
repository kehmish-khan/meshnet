package com.meshnet.grpc

import android.util.Log
import com.google.protobuf.ByteString
import com.meshnet.crypto.CryptoManager
import com.meshnet.crypto.EncryptedPayload
import com.meshnet.db.MeshDatabase
import com.meshnet.mesh.MeshRouter
import com.meshnet.model.Message
import com.meshnet.model.MessageStatus
import com.meshnet.model.Peer
import com.meshnet.model.TransportType
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "MeshGrpcService"

/**
 * MeshGrpcServiceImpl
 *
 * Implements the gRPC service defined in mesh.proto.
 * This is the gRPC layer that sits between the UI/business logic
 * and the actual Bluetooth/WiFi transport layers.
 *
 * gRPC gives us:
 *  - Strongly typed message contracts (via protobuf)
 *  - Clean RPC-style API for inter-device communication
 *  - Automatic serialisation to binary protobuf format
 *  - Easy extension — add LoRa later by adding a new transport
 *    under the same gRPC service interface
 */
class MeshGrpcServiceImpl(
    private val crypto: CryptoManager,
    private val router: MeshRouter,
    private val db: MeshDatabase
) : MeshServiceGrpc.MeshServiceImplBase() {

    private val scope = CoroutineScope(Dispatchers.IO)

    // ─── SendMessage ─────────────────────────────────────────────────────────

    /**
     * Called when THIS device wants to send a message.
     * UI calls this via the gRPC stub.
     *
     * Flow:
     *  1. Encrypt plaintext using recipient's public key
     *  2. Sign the ciphertext with our private key
     *  3. Build ChatMessage proto
     *  4. Pass to MeshRouter for delivery
     */
    fun sendNewMessage(
        toPublicKey: String,
        plaintext: String,
        onResult: (Boolean) -> Unit
    ) {
        scope.launch {
            try {
                // 1. Encrypt
                val payload = crypto.encrypt(plaintext, toPublicKey)

                // 2. Sign (message_id + ciphertext)
                val messageId = UUID.randomUUID().toString()
                val dataToSign = (messageId + payload.ciphertext.contentToString()).toByteArray()
                val signature = crypto.sign(dataToSign)

                // 3. Build Room Message object
                val message = Message(
                    messageId = messageId,
                    fromKey = crypto.getPublicKey(),
                    toKey = toPublicKey,
                    plaintext = plaintext,
                    ciphertext = payload.ciphertext,
                    nonce = payload.nonce,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.PENDING,
                    transport = "BT"
                )

                // 4. Store in local DB
                db.messageDao().insertMessage(message)

                // 5. Route into mesh
                router.sendMessage(message)

                Log.d(TAG, "gRPC SendMessage → $messageId to ${toPublicKey.take(8)}")
                onResult(true)

            } catch (e: Exception) {
                Log.e(TAG, "sendNewMessage failed: ${e.message}")
                onResult(false)
            }
        }
    }

    // ─── SendMessage (gRPC remote call) ─────────────────────────────────────

    /**
     * This is called when a REMOTE device sends us a message via gRPC.
     * Used when devices are on the same local network (e.g. WiFi Direct group).
     */
    override fun sendMessage(
        request: ChatMessage,
        responseObserver: StreamObserver<Ack>
    ) {
        scope.launch {
            try {
                // Verify signature
                val dataToVerify = (request.messageId + request.ciphertext.toByteArray().contentToString()).toByteArray()
                val signatureValid = crypto.verify(
                    dataToVerify,
                    request.signature.toByteArray(),
                    request.fromKey
                )

                if (!signatureValid) {
                    Log.w(TAG, "Signature verification FAILED for ${request.messageId}")
                    responseObserver.onNext(
                        Ack.newBuilder().setMessageId(request.messageId).setDelivered(false).build()
                    )
                    responseObserver.onCompleted()
                    return@launch
                }

                // Convert proto → Room Message
                val message = Message(
                    messageId = request.messageId,
                    fromKey = request.fromKey,
                    toKey = request.toKey,
                    plaintext = "", // will be decrypted if for us
                    ciphertext = request.ciphertext.toByteArray(),
                    nonce = request.nonce.toByteArray(),
                    timestamp = request.timestamp,
                    status = MessageStatus.PENDING,
                    hopsUsed = CryptoManager.TTL_DEFAULT - request.ttl,
                    transport = request.transport
                )

                router.onMessageReceived(message)

                responseObserver.onNext(
                    Ack.newBuilder()
                        .setMessageId(request.messageId)
                        .setDelivered(true)
                        .setRelayKey(crypto.getPublicKey())
                        .build()
                )
                responseObserver.onCompleted()

            } catch (e: Exception) {
                Log.e(TAG, "gRPC sendMessage error: ${e.message}")
                responseObserver.onError(e)
            }
        }
    }

    // ─── ForwardMessage ──────────────────────────────────────────────────────

    /**
     * Called by relay nodes to forward a message through this node.
     * This node is acting as a mesh relay — not sender or recipient.
     */
    override fun forwardMessage(
        request: ChatMessage,
        responseObserver: StreamObserver<Ack>
    ) {
        scope.launch {
            val message = Message(
                messageId = request.messageId,
                fromKey = request.fromKey,
                toKey = request.toKey,
                plaintext = "",
                ciphertext = request.ciphertext.toByteArray(),
                nonce = request.nonce.toByteArray(),
                timestamp = request.timestamp,
                status = MessageStatus.PENDING,
                hopsUsed = CryptoManager.TTL_DEFAULT - request.ttl,
                transport = request.transport
            )

            router.onMessageReceived(message)

            responseObserver.onNext(
                Ack.newBuilder()
                    .setMessageId(request.messageId)
                    .setDelivered(true)
                    .setRelayKey(crypto.getPublicKey())
                    .build()
            )
            responseObserver.onCompleted()
        }
    }

    // ─── DiscoverPeers ───────────────────────────────────────────────────────

    /**
     * Returns list of all peers this node can currently see.
     * Used by neighboring nodes to build their routing tables.
     */
    override fun discoverPeers(
        request: DiscoverRequest,
        responseObserver: StreamObserver<PeerList>
    ) {
        val peers = router.getActivePeers()

        val peerProtos = peers.map { peer ->
            PeerInfo.newBuilder()
                .setPublicKey(peer.publicKey)
                .setDisplayName(peer.displayName)
                .setTransport(if (peer.transport == TransportType.BLUETOOTH) "BT" else "WIFI")
                .setSignalRssi(peer.rssi)
                .setLastSeen(peer.lastSeen)
                .build()
        }

        responseObserver.onNext(
            PeerList.newBuilder().addAllPeers(peerProtos).build()
        )
        responseObserver.onCompleted()

        Log.d(TAG, "discoverPeers → returned ${peers.size} peers")
    }

    // ─── ExchangeIdentity ────────────────────────────────────────────────────

    /**
     * Two-way identity exchange — both parties share their public keys.
     * Called during contact addition (QR scan or proximity exchange).
     */
    override fun exchangeIdentity(
        request: IdentityPayload,
        responseObserver: StreamObserver<IdentityPayload>
    ) {
        // Store their identity as a contact
        scope.launch {
            val contact = com.meshnet.model.Contact(
                publicKey = request.publicKey,
                signPublicKey = "",
                displayName = request.displayName,
                fingerprint = request.fingerprint
            )
            db.contactDao().insertContact(contact)
            Log.d(TAG, "Identity exchanged with ${request.displayName}")
        }

        // Return our identity
        responseObserver.onNext(
            IdentityPayload.newBuilder()
                .setPublicKey(crypto.getPublicKey())
                .setDisplayName(crypto.getUsername())
                .setFingerprint(crypto.getFingerprint())
                .build()
        )
        responseObserver.onCompleted()
    }
}
