package com.meshnet.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.meshnet.MeshNetApp
import com.meshnet.databinding.ActivityChatBinding
import com.meshnet.mesh.MessageRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ChatActivity
 *
 * The main chat screen between two users.
 * Features:
 *  - Real-time message flow via Room database Flow
 *  - Message status indicators (pending/sent/delivered)
 *  - Transport info per message (BT/WiFi, hop count)
 *  - E2E encryption indicator on each message
 *  - Send via gRPC → router → Bluetooth/WiFi
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var repo: MessageRepository
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var peerKey: String
    private lateinit var peerName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerKey  = intent.getStringExtra("peerKey") ?: return finish()
        peerName = intent.getStringExtra("peerName") ?: "Unknown"

        repo = MessageRepository(
            MeshNetApp.database,
            MeshNetApp.crypto,
            MeshNetApp.grpcService,
            MeshNetApp.router
        )

        setupToolbar()
        setupRecyclerView()
        setupSendButton()
        observeMessages()
        updatePeerStatus()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = peerName
            subtitle = peerKey.take(8) + "..." + peerKey.takeLast(8)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(myKey = MeshNetApp.crypto.getPublicKey())
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        binding.etMessage.setText("")
        binding.btnSend.isEnabled = false

        lifecycleScope.launch {
            val sent = repo.sendMessage(peerKey, text)
            binding.btnSend.isEnabled = true
            if (!sent) {
                // Message queued for store & forward
                binding.tvQueuedNotice.visibility = View.VISIBLE
                kotlinx.coroutines.delay(3000)
                binding.tvQueuedNotice.visibility = View.GONE
            }
        }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            repo.getConversation(peerKey).collectLatest { messages ->
                messageAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun updatePeerStatus() {
        lifecycleScope.launch {
            while (true) {
                val peers = repo.getActivePeers()
                val peer = peers.find { it.publicKey == peerKey }
                binding.tvPeerStatus.text = if (peer != null) {
                    "${peer.transport.name} · ${peer.hops} hop · RSSI ${peer.rssi}dBm"
                } else {
                    "Searching mesh..."
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }
}
