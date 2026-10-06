package com.ipphone.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ipphone.data.local.database.entity.CallLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {
    
    @Query("SELECT * FROM call_logs ORDER BY created_at DESC")
    fun getAllCallLogs(): Flow<List<CallLogEntity>>
    
    @Query("SELECT * FROM call_logs WHERE peer_id = :peerId ORDER BY created_at DESC")
    fun getCallLogsForPeer(peerId: String): Flow<List<CallLogEntity>>
    
    @Query("SELECT * FROM call_logs WHERE id = :callId")
    suspend fun getCallById(callId: String): CallLogEntity?
    
    @Query("SELECT * FROM call_logs WHERE status = 'MISSED' AND peer_id = :peerId ORDER BY created_at DESC")
    fun getMissedCalls(peerId: String): Flow<List<CallLogEntity>>
    
    @Query("SELECT COUNT(*) FROM call_logs WHERE status = 'MISSED'")
    fun getMissedCallCount(): Flow<Int>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(callLog: CallLogEntity)
    
    @Update
    suspend fun update(callLog: CallLogEntity)
    
    @Query("UPDATE call_logs SET status = :status, ended_at = :endedAt, duration = :duration WHERE id = :callId")
    suspend fun updateCallStatus(callId: String, status: String, endedAt: Long, duration: Long)
    
    @Query("DELETE FROM call_logs WHERE id = :callId")
    suspend fun deleteById(callId: String)
    
    @Query("DELETE FROM call_logs WHERE peer_id = :peerId")
    suspend fun deleteAllForPeer(peerId: String)
    
    @Query("DELETE FROM call_logs")
    suspend fun deleteAll()
}
