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

    /** v3: composite index so "newest N messages of a chat" never scans the whole table. */
    private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_created_at` " +
                    "ON `messages` (`conversation_id`, `created_at`)"
            )
        }
    }
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): IPPhoneDatabase {
        return Room.databaseBuilder(
            context,
            IPPhoneDatabase::class.java,
            Constants.DATABASE_NAME
        ).addMigrations(MIGRATION_2_3)
            // Safety net only for versions without a migration path.
            .fallbackToDestructiveMigration()
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

