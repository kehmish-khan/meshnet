package com.meshnet

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshnet.crypto.CryptoManager
import com.meshnet.db.MeshDatabase
import com.meshnet.grpc.MeshGrpcServiceImpl
import com.meshnet.mesh.BluetoothMeshService
import com.meshnet.mesh.MeshRouter
import com.meshnet.mesh.WifiDirectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * MeshNetApp
 *
 * Application class — initialises all singleton components:
 *  - CryptoManager (cryptographic identity)
 *  - MeshDatabase (local Room SQLite)
 *  - MeshRouter (routing engine)
 *  - MeshGrpcServiceImpl (gRPC service layer)
 *
 * All services are accessible statically so services and ViewModels
 * can reach them without dependency injection framework.
 */
class MeshNetApp : Application() {

    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Initialise singletons in order
        crypto = CryptoManager(applicationContext)
        database = MeshDatabase.getInstance(applicationContext)
        router = MeshRouter(database, crypto, appScope)
        grpcService = MeshGrpcServiceImpl(crypto, router, database)

        // Wire the router's delivery callback
        router.onMessageDeliveredToMe = { message ->
            // Decrypt and store when message arrives for us
            val contact = null // looked up async in service
            try {
                // Note: actual decryption happens in MessageRepository
                // This callback signals the UI via Flow
            } catch (e: Exception) {
                android.util.Log.e("MeshNetApp", "Delivery callback error: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var crypto: CryptoManager
        lateinit var database: MeshDatabase
        lateinit var router: MeshRouter
        lateinit var grpcService: MeshGrpcServiceImpl
    }
}

// ─── Boot Receiver ───────────────────────────────────────────────────────────

/**
 * BootReceiver
 *
 * Automatically restarts mesh services when phone reboots.
 * This means MeshNet is always running in the background
 * ready to relay messages even after a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val crypto = CryptoManager(context)
            if (crypto.hasIdentity()) {
                // Only start if user has set up an identity
                context.startForegroundService(
                    Intent(context, BluetoothMeshService::class.java)
                )
                context.startForegroundService(
                    Intent(context, WifiDirectService::class.java)
                )
                android.util.Log.d("BootReceiver", "MeshNet services restarted after boot")
            }
        }
    }
}
