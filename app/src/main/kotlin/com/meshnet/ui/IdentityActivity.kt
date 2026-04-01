package com.meshnet.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.meshnet.MeshNetApp
import com.meshnet.R
import com.meshnet.databinding.ActivityIdentityBinding
import org.json.JSONObject

/**
 * IdentityActivity
 *
 * Shows the user's cryptographic identity:
 *  - Display name
 *  - Public key (full hex)
 *  - Fingerprint (human-readable key summary)
 *  - QR code encoding {publicKey, signKey, displayName}
 *
 * Other users scan this QR to add you as a contact.
 * No server. No phone number. Just math.
 */
class IdentityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIdentityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIdentityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "My Identity"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        displayIdentity()
    }

    private fun displayIdentity() {
        val crypto = MeshNetApp.crypto

        binding.tvUsername.text    = crypto.getUsername()
        binding.tvPublicKey.text   = crypto.getPublicKey()
        binding.tvFingerprint.text = crypto.getFingerprint()
        binding.tvKeyNote.text     = "Share this QR code with contacts to let them message you.\nNo internet needed — just show the screen."

        generateQrCode()
    }

    private fun generateQrCode() {
        try {
            val crypto = MeshNetApp.crypto
            // Encode identity as JSON in the QR
            val payload = JSONObject().apply {
                put("pub",  crypto.getPublicKey())
                put("sign", crypto.getSignPublicKey())
                put("name", crypto.getUsername())
                put("fp",   crypto.getFingerprint())
            }.toString()

            val writer = MultiFormatWriter()
            val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 600, 600)
            val encoder = BarcodeEncoder()
            val bitmap: Bitmap = encoder.createBitmap(matrix)
            binding.ivQrCode.setImageBitmap(bitmap)

        } catch (e: Exception) {
            binding.tvKeyNote.text = "QR generation failed: ${e.message}"
        }
    }
}
