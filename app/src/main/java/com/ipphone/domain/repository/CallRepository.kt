package com.ipphone.domain.repository

import com.ipphone.domain.model.CallLog
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    fun getAllCallLogs(): Flow<List<CallLog>>
    fun getCallLogsForPeer(peerId: String): Flow<List<CallLog>>
    suspend fun getCallById(callId: String): CallLog?
    fun getMissedCallCount(): Flow<Int>
    suspend fun saveCallLog(callLog: CallLog)
    suspend fun updateCallStatus(callId: String, status: String, endedAt: Long, duration: Long)
    suspend fun deleteCallLog(callId: String)
    suspend fun deleteAllForPeer(peerId: String)
}
