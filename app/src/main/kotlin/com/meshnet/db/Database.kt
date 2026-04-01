package com.meshnet.db

import androidx.room.*
import com.meshnet.model.Contact
import com.meshnet.model.Message
import com.meshnet.model.MessageStatus
import kotlinx.coroutines.flow.Flow

// ─── Contact DAO ──────────────────────────────────────────────────────────

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE publicKey = :key LIMIT 1")
    suspend fun getContactByKey(key: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("UPDATE contacts SET lastSeen = :time WHERE publicKey = :key")
    suspend fun updateLastSeen(key: String, time: Long)
}

// ─── Message DAO ──────────────────────────────────────────────────────────

@Dao
interface MessageDao {

    // All messages for a specific conversation (both directions)
    @Query("""
        SELECT * FROM messages 
        WHERE (fromKey = :myKey AND toKey = :peerKey) 
           OR (fromKey = :peerKey AND toKey = :myKey)
        ORDER BY timestamp ASC
    """)
    fun getConversation(myKey: String, peerKey: String): Flow<List<Message>>

    // All conversations — latest message per peer
    @Query("""
        SELECT * FROM messages 
        WHERE messageId IN (
            SELECT messageId FROM messages m2
            WHERE (m2.fromKey = :myKey OR m2.toKey = :myKey)
            GROUP BY CASE 
                WHEN m2.fromKey = :myKey THEN m2.toKey 
                ELSE m2.fromKey 
            END
            HAVING timestamp = MAX(timestamp)
        )
        ORDER BY timestamp DESC
    """)
    fun getLatestPerConversation(myKey: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: Message)

    @Query("UPDATE messages SET status = :status WHERE messageId = :id")
    suspend fun updateStatus(id: String, status: MessageStatus)

    @Query("SELECT * FROM messages WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<Message>

    // Check if we've already seen this message ID (deduplication)
    @Query("SELECT COUNT(*) FROM messages WHERE messageId = :id")
    suspend fun messageExists(id: String): Int

    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteOldMessages(cutoff: Long)
}

// ─── Type Converters ──────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray): String =
        android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)

    @TypeConverter
    fun toByteArray(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    @TypeConverter
    fun fromMessageStatus(status: com.meshnet.model.MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): com.meshnet.model.MessageStatus =
        com.meshnet.model.MessageStatus.valueOf(value)
}

// ─── Database ─────────────────────────────────────────────────────────────

@Database(
    entities = [Contact::class, Message::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: MeshDatabase? = null

        fun getInstance(context: android.content.Context): MeshDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MeshDatabase::class.java,
                    "meshnet.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
