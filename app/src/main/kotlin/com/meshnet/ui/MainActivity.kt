package com.meshnet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.meshnet.MeshNetApp
import com.meshnet.R
import com.meshnet.databinding.ActivityMainBinding
import com.meshnet.mesh.MessageRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity
 *
 * Shows:
 *  1. List of contacts with last message preview
 *  2. Mesh status bar (peers connected, transport active)
 *  3. FAB to add contacts (via QR or manual key entry)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: MessageRepository
    private lateinit var contactAdapter: ContactAdapter

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Snackbar.make(
                binding.root,
                "Bluetooth and location permissions are required for mesh networking",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repo = MessageRepository(
            MeshNetApp.database,
            MeshNetApp.crypto,
            MeshNetApp.grpcService,
            MeshNetApp.router
        )

        requestRequiredPermissions()
        setupRecyclerView()
        setupFab()
        observeContacts()
        updateMeshStatus()
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            required += listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter { contact ->
            // Open chat with tapped contact
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("peerKey", contact.publicKey)
                putExtra("peerName", contact.displayName)
            }
            startActivity(intent)
        }
        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddContact.setOnClickListener {
            // Open add contact screen (QR scan or manual entry)
            startActivity(Intent(this, AddContactActivity::class.java))
        }
    }

    private fun observeContacts() {
        lifecycleScope.launch {
            repo.getContacts().collectLatest { contacts ->
                contactAdapter.submitList(contacts)
                binding.tvEmptyState.visibility =
                    if (contacts.isEmpty()) android.view.View.VISIBLE
                    else android.view.View.GONE
            }
        }
    }

    private fun updateMeshStatus() {
        lifecycleScope.launch {
            while (true) {
                val peers = repo.getActivePeers()
                binding.tvMeshStatus.text =
                    "${peers.size} peer${if (peers.size != 1) "s" else ""} · " +
                    "BT + WiFi · ${repo.getPendingCount()} queued"
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_identity -> {
                startActivity(Intent(this, IdentityActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
