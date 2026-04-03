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
import com.meshnet.MeshNetApp
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
private val MESH_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-567812345678")
private const val SERVICE_NAME = "MeshNetService"

class BluetoothMeshService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var crypto: CryptoManager
    private lateinit var db: MeshDatabase
    private lateinit var router: MeshRouter
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var serverSocket: BluetoothServerSocket? = null
    private val connections = mutableMapOf<String, BluetoothSocket>()
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        crypto = CryptoManager(applicationContext)
        db = MeshDatabase.getInstance(applicationContext)
        router = (application as MeshNetApp).router

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        serverSocket?.close()
        connections.values.forEach { runCatching { it.close() } }
        runCatching { unregisterReceiver(discoveryReceiver) }
    }

    private suspend fun startServer() {
        try {
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, MESH_UUID)
            Log.d(TAG, "RFCOMM server listening")
            while (true) {
                val socket = withContext(Dispatchers.IO) { serverSocket?.accept() } ?: break
                Log.d(TAG, "Incoming connection from ${socket.remoteDevice.address}")
                scope.launch { handleConnection(socket) }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server socket error: ${e.message}")
        }
    }

    private suspend fun handleConnection(socket: BluetoothSocket) {
        connections[socket.remoteDevice.address] = socket
        val peer = Peer(
            publicKey = socket.remoteDevice.address,
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
            runCatching { socket.close() }
        }
    }

    private fun processIncomingJson(json: String, senderAddress: String) {
        try {
            val wire = gson.fromJson(json, WireMessage::class.java)
            when (wire.type) {
                "IDENTITY" -> {
                    val identity = gson.fromJson(wire.payload, IdentityWire::class.java)
                    val peer = Peer(
                        publicKey = identity.publicKey,
                        displayName = identity.displayName,
                        transport = TransportType.BLUETOOTH,
                        rssi = -70
                    )
                    router.peerDiscovered(peer)
                }
                "MESSAGE" -> {
                    val msg = gson.fromJson(wire.payload, Message::class.java)
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
            Log.e(TAG, "Failed to parse JSON: ${e.message}")
        }
    }

    private fun sendMessageToPeer(message: Message, nextHopKey: String) {
        scope.launch {
            val socket = connections.values.firstOrNull { it.isConnected }
            if (socket == null) {
                connectToPeer(nextHopKey, message)
                return@launch
            }
            try {
                val wire = WireMessage("MESSAGE", gson.toJson(message))
                socket.outputStream.write(gson.toJson(wire).toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send failed: ${e.message}")
            }
        }
    }

    private suspend fun connectToPeer(address: String, pendingMessage: Message? = null) {
        try {
            val device = bluetoothAdapter.getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(MESH_UUID)
            withContext(Dispatchers.IO) { socket.connect() }
            connections[address] = socket
            sendIdentity(socket)
            pendingMessage?.let { sendMessageToPeer(it, address) }
            scope.launch { handleConnection(socket) }
        } catch (e: IOException) {
            Log.e(TAG, "Connect failed: ${e.message}")
        }
    }

    private fun sendIdentity(socket: BluetoothSocket) {
        val identityWire = IdentityWire(
            publicKey = crypto.getPublicKey(),
            signPublicKey = crypto.getSignPublicKey(),
            displayName = crypto.getUsername()
        )
        val wire = WireMessage("IDENTITY", gson.toJson(identityWire))
        socket.outputStream.write(gson.toJson(wire).toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    private fun startDiscovery() {
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        bluetoothAdapter.startDiscovery()
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    scope.launch { connectToPeer(device.address) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scope.launch { delay(30_000); startDiscovery() }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MeshNet Bluetooth", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshNet Active")
            .setContentText("Bluetooth mesh running")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

data class WireMessage(val type: String, val payload: String)
data class IdentityWire(val publicKey: String, val signPublicKey: String, val displayName: String)
data class AckWire(val messageId: String, val delivered: Boolean)
