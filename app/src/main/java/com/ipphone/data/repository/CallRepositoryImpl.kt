package com.ipphone.data.repository

import com.ipphone.data.local.database.dao.CallLogDao
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.data.mapper.toDomain
import com.ipphone.data.mapper.toEntity
import com.ipphone.domain.model.CallLog
import com.ipphone.domain.repository.CallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val callLogDao: CallLogDao,
    private val userDao: UserDao
) : CallRepository {
    
    override fun getAllCallLogs(): Flow<List<CallLog>> {
        return callLogDao.getAllCallLogs().map { logs ->
            logs.map { log ->
                val user = userDao.getUserById(log.peerId)
                log.toDomain(
                    peerName = user?.deviceName ?: "Unknown",
                    peerImage = user?.profileImage
                )
            }
        }
    }
    
    override fun getCallLogsForPeer(peerId: String): Flow<List<CallLog>> {
        return callLogDao.getCallLogsForPeer(peerId).map { logs ->
            val user = userDao.getUserById(peerId)
            logs.map { log ->
                log.toDomain(
                    peerName = user?.deviceName ?: "Unknown",
                    peerImage = user?.profileImage
                )
            }
        }
    }
    
    override suspend fun getCallById(callId: String): CallLog? {
        val log = callLogDao.getCallById(callId) ?: return null
        val user = userDao.getUserById(log.peerId)
        return log.toDomain(
            peerName = user?.deviceName ?: "Unknown",
            peerImage = user?.profileImage
        )
    }
    
    override fun getMissedCallCount(): Flow<Int> {
        return callLogDao.getMissedCallCount()
    }
    
    override suspend fun saveCallLog(callLog: CallLog) {
        callLogDao.insert(callLog.toEntity())
    }
    
    override suspend fun updateCallStatus(callId: String, status: String, endedAt: Long, duration: Long) {
        callLogDao.updateCallStatus(callId, status, endedAt, duration)
    }
    
    override suspend fun deleteCallLog(callId: String) {
        callLogDao.deleteById(callId)
    }
    
    override suspend fun deleteAllForPeer(peerId: String) {
        callLogDao.deleteAllForPeer(peerId)
    }
}
