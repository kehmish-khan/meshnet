package com.meshnet.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.meshnet.R
import com.meshnet.model.Contact
import com.meshnet.model.Message
import com.meshnet.model.MessageStatus
import java.text.SimpleDateFormat
import java.util.*

// ─── Contact Adapter ─────────────────────────────────────────────────────────

class ContactAdapter(
    private val onClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactAdapter.ContactVH>(ContactDiff) {

    inner class ContactVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView    = view.findViewById(R.id.tv_contact_name)
        val tvKey: TextView     = view.findViewById(R.id.tv_contact_key)
        val tvInitials: TextView = view.findViewById(R.id.tv_initials)

        fun bind(contact: Contact) {
            tvName.text = contact.displayName
            tvKey.text  = contact.publicKey.take(8) + "..." + contact.publicKey.takeLast(8)
            tvInitials.text = contact.displayName.take(2).uppercase()
            itemView.setOnClickListener { onClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactVH =
        ContactVH(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun onBindViewHolder(holder: ContactVH, position: Int) =
        holder.bind(getItem(position))

    object ContactDiff : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(a: Contact, b: Contact) = a.publicKey == b.publicKey
        override fun areContentsTheSame(a: Contact, b: Contact) = a == b
    }
}

// ─── Message Adapter ─────────────────────────────────────────────────────────

class MessageAdapter(
    private val myKey: String
) : ListAdapter<Message, MessageAdapter.MessageVH>(MessageDiff) {

    companion object {
        private const val VIEW_SENT = 1
        private const val VIEW_RECV = 2
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).fromKey == myKey) VIEW_SENT else VIEW_RECV
    }

    inner class MessageVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView    = view.findViewById(R.id.tv_message_text)
        val tvTime: TextView    = view.findViewById(R.id.tv_message_time)
        val tvMeta: TextView    = view.findViewById(R.id.tv_message_meta)
        val tvStatus: TextView? = view.findViewById(R.id.tv_message_status)

        fun bind(msg: Message) {
            tvText.text = msg.plaintext
            tvTime.text = TIME_FMT.format(Date(msg.timestamp))

            // Show transport + hop info + encryption badge
            tvMeta.text = buildString {
                append("E2E · ")
                append(msg.transport)
                if (msg.hopsUsed > 0) append(" · ${msg.hopsUsed} hop")
            }

            // Status for sent messages
            tvStatus?.text = when (msg.status) {
                MessageStatus.PENDING   -> "⏳"
                MessageStatus.SENT      -> "✓"
                MessageStatus.DELIVERED -> "✓✓"
                MessageStatus.FAILED    -> "✗"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
        val layout = if (viewType == VIEW_SENT) R.layout.item_message_sent
                     else R.layout.item_message_received
        return MessageVH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: MessageVH, position: Int) =
        holder.bind(getItem(position))

    object MessageDiff : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.messageId == b.messageId
        override fun areContentsTheSame(a: Message, b: Message) = a == b
    }
}
