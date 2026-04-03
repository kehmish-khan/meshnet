package com.meshnet.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.meshnet.R
import com.meshnet.crypto.CryptoManager
import com.meshnet.db.MeshDatabase
import com.meshnet.model.Message
import com.meshnet.model.MessageStatus
import com.meshnet.model.Peer
import com.meshnet.model.TransportType
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID

private const val TAG = "BluetoothMeshService"
private const val CHANNEL_ID = "meshnet_bt"
private const val NOTIFICATION_ID = 1

// MeshNet's Bluetooth service UUID — all devices use this same UUID
// so they recognise each other as MeshNet nodes
private val MESH_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-567812345678")
private const val SERVICE_NAME = "MeshNetService"

/**
 * BluetoothMeshService
 *
 * A foreground Android Service that:
 *  1. Runs a RFCOMM Bluetooth server socket — accepts connections from peers
 *  2. Handles outgoing connections to known peers
 *  3. Discovers nearby Bluetooth devices (classic discovery)
 *  4. Serialises/deserialises messages as JSON over the socket stream
 *  5. Feeds all received messages into MeshRouter
 *
 * Runs as a foreground service so Android doesn't kill it in the background.
 */
class BluetoothMeshService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var crypto: CryptoManager
    private lateinit var db: MeshDatabase
    private lateinit var router: MeshRouter
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var serverSocket: BluetoothServerSocket? = null
    private val gson = Gson()

    // Active connections: peerAddress → socket
    private val connections = mutableMapOf<String, BluetoothSocket>()

    // ─── Service lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        crypto = CryptoManager(applicationContext)
        db = MeshDatabase.getInstance(applicationContext)
       router = (application as com.meshnet.MeshNetApp).router

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Wire router callbacks
        router.onSendViaBluetooth = { message, nextHopKey ->
            sendMessageToPeer(message, nextHopKey)
        }

        scope.launch { startServer() }
        scope.launch { startDiscovery() }

        registerReceiver(discoveryReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })

        Log.d(TAG, "BluetoothMeshService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY  // restart if killed

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        serverSocket?.close()
        connections.values.forEach { it.close() }
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        Log.d(TAG, "BluetoothMeshService stopped")
    }

    // ─── Server socket (accept incoming connections) ─────────────────────────

    private suspend fun startServer() {
        try {
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                SERVICE_NAME, MESH_UUID
            )
            Log.d(TAG, "RFCOMM server listening on $MESH_UUID")

            while (true) {
                val socket = withContext(Dispatchers.IO) {
                    serverSocket?.accept()   // blocks until a peer connects
                } ?: break

                Log.d(TAG, "Incoming connection from ${socket.remoteDevice.address}")
                scope.launch { handleConnection(socket) }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server socket error: ${e.message}")
        }
    }

    // ─── Connection handler (read loop) ─────────────────────────────────────

    private suspend fun handleConnection(socket: BluetoothSocket) {
        connections[socket.remoteDevice.address] = socket

        // Register this device as a peer in the router
        val peer = Peer(
            publicKey = socket.remoteDevice.address, // temp key until identity exchange
            displayName = socket.remoteDevice.name ?: "Unknown",
            transport = TransportType.BLUETOOTH,
            rssi = -70
        )
        router.peerDiscovered(peer)

        val inputStream = socket.inputStream
        val buffer = ByteArray(4096)

        try {
            while (socket.isConnected) {
                val bytes = withContext(Dispatchers.IO) { inputStream.read(buffer) }
                if (bytes > 0) {
                    val json = String(buffer, 0, bytes, Charsets.UTF_8)
                    processIncomingJson(json, socket.remoteDevice.address)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Connection closed: ${socket.remoteDevice.address}")
        } finally {
            connections.remove(socket.remoteDevice.address)
            router.peerLost(socket.remoteDevice.address)
            socket.close()
        }
    }

    // ─── JSON processing ────────────────────────────────────────────────────

    private fun processIncomingJson(json: String, senderAddress: String) {
        try {
            val wire = gson.fromJson(json, WireMessage::class.java)

            when (wire.type) {
                "IDENTITY" -> {
                    // Peer sent their identity — update routing table with real key
                    val identity = gson.fromJson(wire.payload, IdentityWire::class.java)
                    val peer = Peer(
                        publicKey = identity.publicKey,
                        displayName = identity.displayName,
                        transport = TransportType.BLUETOOTH,
                        rssi = -70
                    )
                    router.peerDiscovered(peer)
                    Log.d(TAG, "Identity received from ${identity.displayName}")
                }

                "MESSAGE" -> {
                    val msg = gson.fromJson(wire.payload, Message::class.java)
                    Log.d(TAG, "Message received: ${msg.messageId} → ${msg.toKey.take(8)}")
                    router.onMessageReceived(msg)
                }

                "ACK" -> {
                    val ack = gson.fromJson(wire.payload, AckWire::class.java)
                    scope.launch(Dispatchers.IO) {
                        db.messageDao().updateStatus(ack.messageId, MessageStatus.DELIVERED)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming JSON: ${e.message}")
        }
    }

    // ─── Send to peer ────────────────────────────────────────────────────────

    private fun sendMessageToPeer(message: Message, nextHopKey: String) {
        scope.launch {
            // Find a connection matching this peer key
            val socket = connections.values.firstOrNull {
                it.remoteDevice.address == nextHopKey || it.isConnected
            }

            if (socket == null || !socket.isConnected) {
                Log.w(TAG, "No active connection to $nextHopKey, attempting connect")
                connectToPeer(nextHopKey, message)
                return@launch
            }

            try {
                val wire = WireMessage(
                    type = "MESSAGE",
                    payload = gson.toJson(message)
                )
                socket.outputStream.write(gson.toJson(wire).toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                Log.d(TAG, "Message sent: ${message.messageId}")
            } catch (e: IOException) {
                Log.e(TAG, "Send failed: ${e.message}")
                scope.launch(Dispatchers.IO) {
                    db.messageDao().updateStatus(message.messageId, MessageStatus.FAILED)
                }
            }
        }
    }

    // ─── Outgoing connection ─────────────────────────────────────────────────

    private suspend fun connectToPeer(address: String, pendingMessage: Message? = null) {
        try {
            val device = bluetoothAdapter.getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(MESH_UUID)

            withContext(Dispatchers.IO) { socket.connect() }
            Log.d(TAG, "Connected to $address")

            connections[address] = socket

            // Send our identity first
            sendIdentity(socket)

            // Then send pending message
            pendingMessage?.let { sendMessageToPeer(it, address) }

            // Start read loop
            scope.launch { handleConnection(socket) }

        } catch (e: IOException) {
            Log.e(TAG, "Connect failed to $address: ${e.message}")
        }
    }

    // ─── Identity exchange ───────────────────────────────────────────────────

    private fun sendIdentity(socket: BluetoothSocket) {
        val identityWire = IdentityWire(
            publicKey = crypto.getPublicKey(),
            signPublicKey = crypto.getSignPublicKey(),
            displayName = crypto.getUsername()
        )
        val wire = WireMessage(type = "IDENTITY", payload = gson.toJson(identityWire))
        socket.outputStream.write(gson.toJson(wire).toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    // ─── Bluetooth Discovery ─────────────────────────────────────────────────

    private fun startDiscovery() {
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        bluetoothAdapter.startDiscovery()
        Log.d(TAG, "Bluetooth discovery started")
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    ) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, -70).toInt()
                    Log.d(TAG, "Found device: ${device.name} (${device.address}) RSSI=$rssi")

                    // Attempt to connect to any found device — it will identify itself
                    scope.launch { connectToPeer(device.address) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Discovery finished, restarting in 30s")
                    scope.launch {
                        delay(30_000)
                        startDiscovery()
                    }
                }
            }
        }
    }

    // ─── Notification (required for foreground service) ──────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MeshNet Bluetooth",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Mesh network running" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshNet Active")
            .setContentText("Bluetooth mesh running — ${connections.size} peers")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

// ─── Wire format data classes ────────────────────────────────────────────────

data class WireMessage(val type: String, val payload: String)
data class IdentityWire(val publicKey: String, val signPublicKey: String, val displayName: String)
data class AckWire(val messageId: String, val delivered: Boolean)
