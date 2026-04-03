package com.meshnet.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.meshnet.crypto.CryptoManager
import com.meshnet.db.MeshDatabase
import com.meshnet.model.Message
import com.meshnet.model.Peer
import com.meshnet.model.TransportType
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "WifiDirectService"
private const val CHANNEL_ID = "meshnet_wifi"
private const val NOTIFICATION_ID = 2
private const val WIFI_PORT = 8988  // TCP port for WiFi Direct mesh

/**
 * WifiDirectService
 *
 * Handles WiFi Direct (P2P) mesh transport.
 * WiFi Direct gives 200–300m range vs Bluetooth's 10–100m.
 *
 * How it works:
 *  1. Discovers nearby WiFi Direct peers
 *  2. Forms a P2P group (one device becomes GO — Group Owner)
 *  3. Opens a TCP socket on port 8988
 *  4. All group members connect to GO's TCP server
 *  5. Messages routed through group via TCP
 *
 * Multiple groups can bridge via shared members — natural mesh extension.
 */
class WifiDirectService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var crypto: CryptoManager
    private lateinit var db: MeshDatabase
    private lateinit var router: MeshRouter

    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    private var groupOwnerAddress: String? = null
    private var isGroupOwner = false
    private var serverSocket: ServerSocket? = null

    private val connections = mutableMapOf<String, Socket>()
    private val gson = Gson()

    // ─── Service lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        crypto = CryptoManager(applicationContext)
        db = MeshDatabase.getInstance(applicationContext)
       router = (application as com.meshnet.MeshNetApp).router

        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(this, mainLooper, null)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        router.onSendViaWifi = { message, nextHopKey ->
            sendMessageToPeer(message, nextHopKey)
        }

        registerReceiver(p2pReceiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })

        discoverPeers()
        Log.d(TAG, "WifiDirectService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        serverSocket?.close()
        connections.values.forEach { it.close() }
        try { unregisterReceiver(p2pReceiver) } catch (_: Exception) {}
        wifiP2pManager.removeGroup(channel, null)
    }

    // ─── Peer discovery ──────────────────────────────────────────────────────

    private fun discoverPeers() {
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "WiFi Direct peer discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "WiFi Direct discovery failed: $reason")
                // Retry after delay
                scope.launch { delay(15_000); discoverPeers() }
            }
        })
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connecting to WiFi Direct peer: ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "WiFi Direct connect failed: $reason")
            }
        })
    }

    // ─── TCP Server (Group Owner) ────────────────────────────────────────────

    private fun startTcpServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(WIFI_PORT)
                Log.d(TAG, "TCP server started on port $WIFI_PORT")

                while (isActive) {
                    val client = withContext(Dispatchers.IO) { serverSocket?.accept() } ?: break
                    Log.d(TAG, "WiFi Direct client connected: ${client.inetAddress}")
                    scope.launch { handleTcpConnection(client) }
                }
            } catch (e: IOException) {
                Log.e(TAG, "TCP server error: ${e.message}")
            }
        }
    }

    // ─── TCP Client (Group Member) ────────────────────────────────────────────

    private fun connectToGroupOwner(ownerAddress: String) {
        scope.launch {
            try {
                val socket = withContext(Dispatchers.IO) {
                    Socket(ownerAddress, WIFI_PORT)
                }
                Log.d(TAG, "Connected to group owner: $ownerAddress")
                connections[ownerAddress] = socket
                sendIdentityTcp(socket)
                scope.launch { handleTcpConnection(socket) }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to connect to group owner: ${e.message}")
            }
        }
    }

    // ─── TCP connection handler ──────────────────────────────────────────────

    private suspend fun handleTcpConnection(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val address = socket.inetAddress.hostAddress ?: "unknown"

        try {
            var line: String?
            while (socket.isConnected) {
                line = withContext(Dispatchers.IO) { reader.readLine() }
                if (line != null) processIncomingJson(line, address)
            }
        } catch (e: IOException) {
            Log.d(TAG, "WiFi connection closed: $address")
        } finally {
            connections.remove(address)
            socket.close()
        }
    }

    // ─── JSON processing ────────────────────────────────────────────────────

    private fun processIncomingJson(json: String, senderAddress: String) {
        try {
            val wire = gson.fromJson(json, WireMessage::class.java)
            when (wire.type) {
                "IDENTITY" -> {
                    val identity = gson.fromJson(wire.payload, IdentityWire::class.java)
                    val peer = Peer(
                        publicKey = identity.publicKey,
                        displayName = identity.displayName,
                        transport = TransportType.WIFI_DIRECT,
                        rssi = -60
                    )
                    router.peerDiscovered(peer)
                    Log.d(TAG, "WiFi identity received: ${identity.displayName}")
                }
                "MESSAGE" -> {
                    val msg = gson.fromJson(wire.payload, Message::class.java)
                    router.onMessageReceived(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi JSON parse error: ${e.message}")
        }
    }

    // ─── Send ────────────────────────────────────────────────────────────────

    private fun sendMessageToPeer(message: Message, nextHopKey: String) {
        val socket = connections.values.firstOrNull { it.isConnected } ?: run {
            Log.w(TAG, "No WiFi connection available for $nextHopKey")
            return
        }
        scope.launch {
            try {
                val wire = WireMessage("MESSAGE", gson.toJson(message))
                val writer = PrintWriter(
                    BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)),
                    true
                )
                writer.println(gson.toJson(wire))
                Log.d(TAG, "WiFi message sent: ${message.messageId}")
            } catch (e: IOException) {
                Log.e(TAG, "WiFi send failed: ${e.message}")
            }
        }
    }

    private fun sendIdentityTcp(socket: Socket) {
        try {
            val identity = IdentityWire(
                publicKey = crypto.getPublicKey(),
                signPublicKey = crypto.getSignPublicKey(),
                displayName = crypto.getUsername()
            )
            val wire = WireMessage("IDENTITY", gson.toJson(identity))
            val writer = PrintWriter(
                BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)),
                true
            )
            writer.println(gson.toJson(wire))
        } catch (e: IOException) {
            Log.e(TAG, "Identity send failed: ${e.message}")
        }
    }

    // ─── WiFi P2P Broadcast Receiver ─────────────────────────────────────────

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager.requestPeers(channel) { peers ->
                        Log.d(TAG, "WiFi Direct peers found: ${peers.deviceList.size}")
                        peers.deviceList.forEach { device ->
                            if (device.status == WifiP2pDevice.AVAILABLE) {
                                connectToPeer(device)
                            }
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager.requestConnectionInfo(channel) { info ->
                            if (info.groupFormed) {
                                isGroupOwner = info.isGroupOwner
                                if (isGroupOwner) {
                                    Log.d(TAG, "This device is Group Owner — starting TCP server")
                                    startTcpServer()
                                } else {
                                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                                    groupOwnerAddress?.let {
                                        Log.d(TAG, "Connecting to Group Owner: $it")
                                        connectToGroupOwner(it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MeshNet WiFi Direct",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshNet WiFi Active")
            .setContentText("WiFi Direct mesh running")
           .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
