package com.meshnet.mesh

import android.util.Log
import com.meshnet.crypto.CryptoManager
import com.meshnet.db.MeshDatabase
import com.meshnet.model.Message
import com.meshnet.model.MessageStatus
import com.meshnet.model.Peer
import com.meshnet.model.RouteEntry
import com.meshnet.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MeshRouter"

/**
 * MeshRouter
 *
 * The brain of the mesh network.
 * Responsibilities:
 *  1. Message deduplication — ensures each message is processed only once
 *  2. TTL management — decrements TTL on each hop, drops at 0
 *  3. Routing table — maps destination keys to next-hop peers
 *  4. Forwarding decision — should this node relay this message?
 *  5. Store & Forward queue — holds messages when no path exists
 *
 * This class is transport-agnostic. BluetoothMeshService and WifiDirectService
 * both feed incoming messages here, and ask here for forwarding decisions.
 */
class MeshRouter(
    private val db: MeshDatabase,
    private val crypto: CryptoManager,
    private val scope: CoroutineScope
) {

    // ─── Seen message IDs (deduplication) ───────────────────────────────────
    // Keeps last 1000 message IDs in memory to prevent re-processing
    private val seenMessages = object : LinkedHashMap<String, Long>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>) = size > 1000
    }

    // ─── Routing table: destinationKey → RouteEntry ──────────────────────────
    private val routingTable = ConcurrentHashMap<String, RouteEntry>()

    // ─── Known peers currently reachable ────────────────────────────────────
    private val activePeers = ConcurrentHashMap<String, Peer>()

    // ─── Store-and-forward queue: messages waiting for a route ───────────────
    private val pendingQueue = ConcurrentHashMap<String, Message>()

    // ─── Callbacks set by services ───────────────────────────────────────────
    var onSendViaBluetooth: ((Message, String) -> Unit)? = null  // (message, nextHopKey)
    var onSendViaWifi: ((Message, String) -> Unit)? = null
    var onMessageDeliveredToMe: ((Message) -> Unit)? = null

    private val myPublicKey: String get() = crypto.getPublicKey()

    // ─── Peer management ────────────────────────────────────────────────────

    fun peerDiscovered(peer: Peer) {
        activePeers[peer.publicKey] = peer
        routingTable[peer.publicKey] = RouteEntry(
            destinationKey = peer.publicKey,
            nextHopKey = peer.publicKey,
            transport = peer.transport,
            hops = peer.hops
        )
        Log.d(TAG, "Peer discovered: ${peer.displayName} via ${peer.transport}")
        // Try to deliver any queued messages for this peer
        flushQueueForPeer(peer.publicKey)
    }

    fun peerLost(publicKey: String) {
        activePeers.remove(publicKey)
        Log.d(TAG, "Peer lost: $publicKey")
    }

    fun getActivePeers(): List<Peer> = activePeers.values.toList()

    // ─── Message ingestion (called by BT/WiFi services) ──────────────────────

    /**
     * Called when any transport receives a message.
     * Decides: is this for me? should I relay it? is it a duplicate?
     */
    fun onMessageReceived(message: Message) {
        // 1. Deduplication check
        if (isDuplicate(message.messageId)) {
            Log.d(TAG, "Dropping duplicate: ${message.messageId}")
            return
        }
        markSeen(message.messageId)

        // 2. TTL check — drop messages that have expired
        if (message.hopsUsed >= CryptoManager.TTL_DEFAULT) {
            Log.d(TAG, "Dropping TTL-expired message: ${message.messageId}")
            return
        }

        // 3. Is this message for me?
        if (message.toKey == myPublicKey) {
            Log.d(TAG, "Message delivered to me: ${message.messageId}")
            onMessageDeliveredToMe?.invoke(message)
            scope.launch(Dispatchers.IO) {
                db.messageDao().insertMessage(message.copy(
                    status = MessageStatus.DELIVERED,
                    isIncoming = true
                ))
            }
            return
        }

        // 4. Not for me — relay it (decrement TTL, find next hop)
        relay(message)
    }

    // ─── Relay logic ────────────────────────────────────────────────────────

    private fun relay(message: Message) {
        val route = routingTable[message.toKey]
            ?: findBestRoute(message.toKey)

        if (route == null) {
            // No route known — add to store-and-forward queue
            Log.d(TAG, "No route for ${message.toKey}, queuing for later")
            pendingQueue[message.messageId] = message
            return
        }

        val relayed = message.copy(hopsUsed = message.hopsUsed + 1)
        Log.d(TAG, "Relaying ${message.messageId} via ${route.transport} to ${route.nextHopKey}")

        when (route.transport) {
            TransportType.BLUETOOTH  -> onSendViaBluetooth?.invoke(relayed, route.nextHopKey)
            TransportType.WIFI_DIRECT -> onSendViaWifi?.invoke(relayed, route.nextHopKey)
        }
    }

    /**
     * When we don't have a direct route, flood to all known peers.
     * Each peer will check if it knows the destination.
     */
    private fun findBestRoute(destinationKey: String): RouteEntry? {
        // If we have active peers, flood — pick the one with best signal
        val bestPeer = activePeers.values
            .filter { it.publicKey != destinationKey }
            .maxByOrNull { it.rssi }
            ?: return null

        return RouteEntry(
            destinationKey = destinationKey,
            nextHopKey = bestPeer.publicKey,
            transport = bestPeer.transport,
            hops = bestPeer.hops + 1
        )
    }

    // ─── Sending outbound messages ───────────────────────────────────────────

    /**
     * Called by UI when user sends a message.
     * Finds route and dispatches to correct transport.
     */
    fun sendMessage(message: Message) {
        markSeen(message.messageId)

        val route = routingTable[message.toKey] ?: findBestRoute(message.toKey)

        if (route == null) {
            Log.d(TAG, "No route yet, queuing message ${message.messageId}")
            pendingQueue[message.messageId] = message
            scope.launch(Dispatchers.IO) {
                db.messageDao().updateStatus(message.messageId, MessageStatus.PENDING)
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            db.messageDao().updateStatus(message.messageId, MessageStatus.SENT)
        }

        when (route.transport) {
            TransportType.BLUETOOTH   -> onSendViaBluetooth?.invoke(message, route.nextHopKey)
            TransportType.WIFI_DIRECT -> onSendViaWifi?.invoke(message, route.nextHopKey)
        }
    }

    // ─── Store & Forward ────────────────────────────────────────────────────

    private fun flushQueueForPeer(peerKey: String) {
        val route = routingTable[peerKey] ?: return
        val toFlush = pendingQueue.values.filter { it.toKey == peerKey }
        toFlush.forEach { msg ->
            pendingQueue.remove(msg.messageId)
            Log.d(TAG, "Flushing queued message ${msg.messageId} to $peerKey")
            when (route.transport) {
                TransportType.BLUETOOTH   -> onSendViaBluetooth?.invoke(msg, peerKey)
                TransportType.WIFI_DIRECT -> onSendViaWifi?.invoke(msg, peerKey)
            }
        }
    }

    // ─── Deduplication helpers ───────────────────────────────────────────────

    private fun isDuplicate(messageId: String): Boolean =
        seenMessages.containsKey(messageId)

    private fun markSeen(messageId: String) {
        seenMessages[messageId] = System.currentTimeMillis()
    }

    // ─── Routing table update ────────────────────────────────────────────────

    fun updateRoute(entry: RouteEntry) {
        val existing = routingTable[entry.destinationKey]
        // Only update if new route is better (fewer hops)
        if (existing == null || entry.hops < existing.hops) {
            routingTable[entry.destinationKey] = entry
        }
    }

    fun getRouteCount(): Int = routingTable.size
    fun getPendingCount(): Int = pendingQueue.size
}
