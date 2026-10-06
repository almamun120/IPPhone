package com.ipphone.domain.repository

import com.ipphone.domain.model.User
import com.ipphone.domain.model.PairingState
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getPairedUsers(): Flow<List<User>>
    fun getBlockedUsers(): Flow<List<User>>
    fun getPendingRequests(): Flow<List<User>>
    fun getUserById(uuid: String): Flow<User?>
    suspend fun getUserByIdOnce(uuid: String): User?
    suspend fun isPaired(uuid: String): Boolean
    suspend fun isBlocked(uuid: String): Boolean
    suspend fun saveUser(user: User)
    suspend fun updatePairingState(uuid: String, state: PairingState)
    suspend fun blockUser(uuid: String)
    suspend fun unblockUser(uuid: String)
    suspend fun updatePresence(uuid: String, ipAddress: String, tcpPort: Int)
    suspend fun getUserByIp(ipAddress: String): User?
    suspend fun deleteUser(uuid: String)
}
