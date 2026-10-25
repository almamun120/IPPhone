package com.ipphone.data.local.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ipphone.data.local.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("""
        SELECT * FROM users 
        WHERE is_blocked = 0 
        AND (EXISTS (SELECT 1 FROM messages WHERE conversation_id = users.uuid)
             OR EXISTS (SELECT 1 FROM call_logs WHERE peer_id = users.uuid AND status != 'FAILED'))
        ORDER BY device_name ASC
    """)
    fun getContacts(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE is_blocked = 1 ORDER BY blocked_at DESC")
    fun getBlockedUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE uuid = :uuid")
    suspend fun getUserById(uuid: String): UserEntity?

    @Query("SELECT * FROM users WHERE uuid = :uuid")
    fun getUserByIdFlow(uuid: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE ip_address = :ipAddress AND tcp_port = :port")
    suspend fun getUserByAddress(ipAddress: String, port: Int): UserEntity?

    @Query("SELECT * FROM users WHERE ip_address = :ipAddress LIMIT 1")
    suspend fun getUserByIp(ipAddress: String): UserEntity?

    /**
     * Returns all non-blocked users that have a stored IP address.
     *
     * Used by PresenceManager and DiscoveryManager to send unicast heartbeats
     * and TCP pings to known peers when NSD/broadcast discovery fails due to
     * AP isolation on office routers.
     */
    @Query("""
        SELECT * FROM users 
        WHERE is_blocked = 0 
        AND ip_address IS NOT NULL 
        AND ip_address != ''
        ORDER BY last_seen DESC
    """)
    suspend fun getAllKnownUsers(): List<UserEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE uuid = :uuid AND is_blocked = 1)")
    suspend fun isBlocked(uuid: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<UserEntity>)

    @Update
    suspend fun update(user: UserEntity)

    @Query("UPDATE users SET is_blocked = 1, blocked_at = :blockedAt, updated_at = :blockedAt WHERE uuid = :uuid")
    suspend fun blockUser(uuid: String, blockedAt: Long = System.currentTimeMillis())

    @Query("UPDATE users SET is_blocked = 0, blocked_at = NULL, updated_at = :updatedAt WHERE uuid = :uuid")
    suspend fun unblockUser(uuid: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE users SET ip_address = :ipAddress, tcp_port = :tcpPort, last_seen = :lastSeen, updated_at = :lastSeen WHERE uuid = :uuid")
    suspend fun updatePresence(uuid: String, ipAddress: String, tcpPort: Int, lastSeen: Long = System.currentTimeMillis())

    @Query("UPDATE users SET last_seen = :lastSeen WHERE uuid = :uuid")
    suspend fun updateLastSeen(uuid: String, lastSeen: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(user: UserEntity)

    @Query("DELETE FROM users WHERE uuid = :uuid")
    suspend fun deleteById(uuid: String)

    @Query("SELECT * FROM users WHERE is_blocked = 0 AND (last_seen IS NULL OR last_seen < :threshold)")
    suspend fun getOfflineUsers(threshold: Long): List<UserEntity>
}