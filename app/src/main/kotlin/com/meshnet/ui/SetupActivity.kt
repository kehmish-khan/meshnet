package com.meshnet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meshnet.MeshNetApp
import com.meshnet.databinding.ActivitySetupBinding
import com.meshnet.mesh.BluetoothMeshService
import com.meshnet.mesh.WifiDirectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SetupActivity
 *
 * First launch screen. Shown only once — when no identity exists.
 * User enters a display name and we generate their cryptographic identity.
 *
 * After setup:
 *  - Keys are stored in SharedPreferences (private, never synced)
 *  - Bluetooth and WiFi Direct services are started
 *  - User is taken to MainActivity
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If identity already exists, skip setup
        if (MeshNetApp.crypto.hasIdentity()) {
            startMeshServices()
            goToMain()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Animate key generation hint
        lifecycleScope.launch {
            val steps = listOf(
                "Initialising Curve25519...",
                "Generating key entropy...",
                "Ed25519 signing keys ready",
                "Enter your name to continue"
            )
            steps.forEach { step ->
                binding.tvKeyStatus.text = step
                delay(700)
            }
        }

        binding.btnCreate.setOnClickListener {
            val name = binding.etUsername.text.toString().trim()
            if (name.isEmpty()) {
                binding.etUsername.error = "Please enter a display name"
                return@setOnClickListener
            }
            if (name.length < 3) {
                binding.etUsername.error = "Name must be at least 3 characters"
                return@setOnClickListener
            }
            createIdentity(name)
        }
    }

    private fun createIdentity(username: String) {
        binding.btnCreate.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvKeyStatus.text = "Generating your keys..."

        lifecycleScope.launch(Dispatchers.IO) {
            // Generate the cryptographic identity
            MeshNetApp.crypto.generateIdentity(username)

            withContext(Dispatchers.Main) {
                binding.tvKeyStatus.text = "Identity created!"
                binding.tvPublicKey.text = "Public key: ${MeshNetApp.crypto.getPublicKey().take(16)}..."
                binding.tvPublicKey.visibility = View.VISIBLE
            }

            delay(1000)

            withContext(Dispatchers.Main) {
                startMeshServices()
                goToMain()
            }
        }
    }

    private fun startMeshServices() {
        startForegroundService(Intent(this, BluetoothMeshService::class.java))
        startForegroundService(Intent(this, WifiDirectService::class.java))
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
