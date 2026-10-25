package com.ipphone.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ipphone.data.local.database.dao.CallLogDao
import com.ipphone.data.local.database.dao.FileTransferDao
import com.ipphone.data.local.database.dao.MessageDao
import com.ipphone.data.local.database.dao.SyncQueueDao
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.data.local.database.entity.CallLogEntity
import com.ipphone.data.local.database.entity.FileTransferEntity
import com.ipphone.data.local.database.entity.MessageEntity
import com.ipphone.data.local.database.entity.SyncQueueEntity
import com.ipphone.data.local.database.entity.TransferFileEntity
import com.ipphone.data.local.database.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        FileTransferEntity::class,
        TransferFileEntity::class,
        CallLogEntity::class,
        SyncQueueEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class IPPhoneDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun callLogDao(): CallLogDao
    abstract fun syncQueueDao(): SyncQueueDao
}

