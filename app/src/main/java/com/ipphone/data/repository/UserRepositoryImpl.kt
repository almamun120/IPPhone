package com.ipphone.data.repository

import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.data.mapper.toDomain
import com.ipphone.data.mapper.toEntity
import com.ipphone.domain.model.User
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.presence.PresenceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val presenceManager: PresenceManager,
    private val preferencesManager: PreferencesManager
) : UserRepository {
    
    override fun getContacts(): Flow<List<User>> {
        return combine(
            userDao.getContacts(),
            presenceManager.onlineDevices
        ) { users, onlineSet ->
            users.map { entity ->
                entity.toDomain(isOnline = onlineSet.contains(entity.uuid))
            }
        }
    }
    
    override fun getBlockedUsers(): Flow<List<User>> {
        return userDao.getBlockedUsers().map { users ->
            users.map { it.toDomain() }
        }
    }
    
    override fun getUserById(uuid: String): Flow<User?> {
        return combine(
            userDao.getUserByIdFlow(uuid),
            presenceManager.onlineDevices
        ) { entity, onlineSet ->
            entity?.toDomain(isOnline = onlineSet.contains(entity.uuid))
        }
    }
    
    override suspend fun getUserByIdOnce(uuid: String): User? {
        return userDao.getUserById(uuid)?.toDomain(
            isOnline = presenceManager.isDeviceOnline(uuid)
        )
    }
    
    override suspend fun isBlocked(uuid: String): Boolean {
        return userDao.isBlocked(uuid)
    }
    
    override suspend fun saveUser(user: User) {
        userDao.insert(user.toEntity())
    }
    
    override suspend fun blockUser(uuid: String) {
        userDao.blockUser(uuid)
    }
    
    override suspend fun unblockUser(uuid: String) {
        userDao.unblockUser(uuid)
    }
    
    override suspend fun updatePresence(uuid: String, ipAddress: String, tcpPort: Int) {
        userDao.updatePresence(uuid, ipAddress, tcpPort)
    }

    override suspend fun getUserByIp(ipAddress: String): User? {
        return userDao.getUserByIp(ipAddress)?.toDomain(
            isOnline = presenceManager.isDeviceOnline(ipAddress) // Best effort
        )
    }
    
    override suspend fun deleteUser(uuid: String) {
        userDao.deleteById(uuid)
    }
}
