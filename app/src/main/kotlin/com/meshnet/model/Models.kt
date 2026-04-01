package com.meshnet.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─── Contact ──────────────────────────────────────────────────────────────

/**
 * A contact stored locally on this device.
 * Added either by QR scan or manual key entry.
 * No server involved — pure local storage.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey
    val publicKey: String,          // Curve25519 public key (hex) — this IS their identity
    val signPublicKey: String = "", // Ed25519 public key for verifying signatures
    val displayName: String,
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0L
)

// ─── Message ──────────────────────────────────────────────────────────────

/**
 * A chat message stored locally.
 * Ciphertext is stored so we can verify delivery acks.
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val messageId: String,          // SHA-256 based unique ID
    val fromKey: String,            // sender public key
    val toKey: String,              // recipient public key
    val plaintext: String,          // decrypted content (only stored after decrypt)
    val ciphertext: ByteArray = byteArrayOf(),
    val nonce: ByteArray = byteArrayOf(),
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.PENDING,
    val hopsUsed: Int = 0,
    val transport: String = "BT",   // "BT" or "WIFI"
    val isIncoming: Boolean = false
)

enum class MessageStatus {
    PENDING,    // queued, not yet sent
    SENT,       // sent to mesh, waiting for ack
    DELIVERED,  // ack received
    FAILED      // TTL expired, never delivered
}

// ─── Peer ─────────────────────────────────────────────────────────────────

/**
 * A nearby device seen on the mesh.
 * Not persisted — discovered dynamically via Bluetooth/WiFi.
 */
data class Peer(
    val publicKey: String,
    val displayName: String,
    val transport: TransportType,
    val rssi: Int = -70,
    val lastSeen: Long = System.currentTimeMillis(),
    val hops: Int = 1
)

enum class TransportType { BLUETOOTH, WIFI_DIRECT }

// ─── Mesh routing ─────────────────────────────────────────────────────────

/**
 * Routing table entry — maps a destination key to the next hop peer.
 */
data class RouteEntry(
    val destinationKey: String,
    val nextHopKey: String,
    val transport: TransportType,
    val hops: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)

// ─── Identity ─────────────────────────────────────────────────────────────

/**
 * This device's identity — passed around for QR/exchange flows.
 */
data class Identity(
    val publicKey: String,
    val signPublicKey: String,
    val displayName: String,
    val fingerprint: String
)
