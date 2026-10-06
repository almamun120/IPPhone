package com.ipphone.di

import android.content.Context
import androidx.room.Room
import com.ipphone.data.local.database.IPPhoneDatabase
import com.ipphone.data.local.database.dao.*
import com.ipphone.utils.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): IPPhoneDatabase {
        return Room.databaseBuilder(
            context,
            IPPhoneDatabase::class.java,
            Constants.DATABASE_NAME
        ).fallbackToDestructiveMigration()
            .build()
    }
    
    @Provides
    @Singleton
    fun provideUserDao(database: IPPhoneDatabase): UserDao {
        return database.userDao()
    }
    
    @Provides
    @Singleton
    fun provideMessageDao(database: IPPhoneDatabase): MessageDao {
        return database.messageDao()
    }
    
    @Provides
    @Singleton
    fun provideFileTransferDao(database: IPPhoneDatabase): FileTransferDao {
        return database.fileTransferDao()
    }
    
    @Provides
    @Singleton
    fun provideCallLogDao(database: IPPhoneDatabase): CallLogDao {
        return database.callLogDao()
    }
    
    @Provides
    @Singleton
    fun provideSyncQueueDao(database: IPPhoneDatabase): SyncQueueDao {
        return database.syncQueueDao()
    }
}

