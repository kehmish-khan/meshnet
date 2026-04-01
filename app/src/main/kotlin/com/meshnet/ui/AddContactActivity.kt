package com.meshnet.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.meshnet.MeshNetApp
import com.meshnet.databinding.ActivityAddContactBinding
import com.meshnet.mesh.MessageRepository
import com.meshnet.model.Contact
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * AddContactActivity
 *
 * Two ways to add a contact:
 *  1. Scan their QR code (the preferred offline method)
 *  2. Manually paste their public key + enter their name
 *
 * No server lookup. No phone number. No internet.
 * Contact is stored in local Room database.
 */
class AddContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddContactBinding
    private lateinit var repo: MessageRepository

    // QR scanner launcher
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            parseQrResult(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Add Contact"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        repo = MessageRepository(
            MeshNetApp.database,
            MeshNetApp.crypto,
            MeshNetApp.grpcService,
            MeshNetApp.router
        )

        binding.btnScanQr.setOnClickListener { startQrScan() }
        binding.btnAddManual.setOnClickListener { addManually() }
    }

    // ─── QR Scan ─────────────────────────────────────────────────────────────

    private fun startQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan contact's QR code")
            setCameraId(0)
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    private fun parseQrResult(json: String) {
        try {
            val obj = JSONObject(json)
            val publicKey   = obj.getString("pub")
            val signKey     = obj.optString("sign", "")
            val displayName = obj.getString("name")
            val fingerprint = obj.optString("fp", "")

            // Pre-fill the form fields
            binding.etContactName.setText(displayName)
            binding.etPublicKey.setText(publicKey)
            binding.tvQrSuccess.visibility = View.VISIBLE
            binding.tvQrSuccess.text = "QR scanned: $displayName"

            // Auto-save after QR scan
            saveContact(publicKey, signKey, displayName, fingerprint)

        } catch (e: Exception) {
            Toast.makeText(this, "Invalid QR code — not a MeshNet identity", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Manual Entry ─────────────────────────────────────────────────────────

    private fun addManually() {
        val name = binding.etContactName.text.toString().trim()
        val key  = binding.etPublicKey.text.toString().trim()

        if (name.isEmpty()) {
            binding.etContactName.error = "Enter a display name"
            return
        }
        if (key.length < 32) {
            binding.etPublicKey.error = "Public key must be at least 32 hex characters"
            return
        }

        // Derive fingerprint from key
        val fingerprint = key.chunked(4).take(12).joinToString(":")
        saveContact(key, "", name, fingerprint)
    }

    // ─── Save ─────────────────────────────────────────────────────────────────

    private fun saveContact(
        publicKey: String,
        signKey: String,
        displayName: String,
        fingerprint: String
    ) {
        // Don't add yourself
        if (publicKey == MeshNetApp.crypto.getPublicKey()) {
            Toast.makeText(this, "That's your own key!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val contact = Contact(
                publicKey    = publicKey,
                signPublicKey = signKey,
                displayName  = displayName,
                fingerprint  = fingerprint
            )
            repo.addContact(contact)
            Toast.makeText(
                this@AddContactActivity,
                "$displayName added to contacts",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
