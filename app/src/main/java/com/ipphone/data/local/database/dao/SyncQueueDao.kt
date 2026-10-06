package com.ipphone.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ipphone.data.local.database.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {
    
    @Query("SELECT * FROM sync_queue WHERE target_device = :targetDevice ORDER BY priority DESC, created_at ASC")
    suspend fun getQueueForDevice(targetDevice: String): List<SyncQueueEntity>
    
    @Query("SELECT * FROM sync_queue WHERE target_device = :targetDevice AND retry_count < max_retries ORDER BY priority DESC, created_at ASC")
    suspend fun getPendingForDevice(targetDevice: String): List<SyncQueueEntity>
    
    @Query("SELECT DISTINCT target_device FROM sync_queue WHERE retry_count < max_retries")
    fun getDevicesWithPendingSync(): Flow<List<String>>
    
    @Query("SELECT COUNT(*) FROM sync_queue WHERE target_device = :targetDevice")
    fun getQueueCountForDevice(targetDevice: String): Flow<Int>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SyncQueueEntity>)
    
    @Query("UPDATE sync_queue SET retry_count = retry_count + 1, last_attempt = :lastAttempt WHERE id = :id")
    suspend fun incrementRetry(id: String, lastAttempt: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM sync_queue WHERE target_device = :targetDevice")
    suspend fun deleteAllForDevice(targetDevice: String)
    
    @Query("DELETE FROM sync_queue WHERE retry_count >= max_retries")
    suspend fun deleteExpired()
}
