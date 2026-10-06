package com.ipphone.di

import android.content.Context
import com.ipphone.data.local.database.dao.SyncQueueDao
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.network.calling.CallManager
import com.ipphone.network.calling.WebRtcManager
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.discovery.DiscoveryManager
import com.ipphone.network.filetransfer.ChatTransferManager
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.network.filetransfer.LiveTransferManager
import com.ipphone.network.messaging.MessagingEngine
import com.ipphone.network.presence.PresenceManager
import com.ipphone.network.sync.SyncManager
import com.ipphone.utils.NotificationHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideDiscoveryManager(
        @ApplicationContext context: Context,
        userDao: UserDao,
        connectionManager: ConnectionManager,
        preferencesManager: PreferencesManager
    ): DiscoveryManager {
        // DiscoveryManager now needs userDao and connectionManager for its
        // TCP-ping-known-peers fallback (office AP isolation workaround).
        // It also needs PreferencesManager for building heartbeat packets
        // during subnet scanning; we inject it via setter to avoid a circular
        // Hilt dependency (ConnectionManager → PreferencesManager already exists).
        val dm = DiscoveryManager(context, userDao, connectionManager)
        dm.setPreferencesManager(preferencesManager)
        return dm
    }

    @Provides
    @Singleton
    fun provideConnectionManager(
        preferencesManager: PreferencesManager,
        userDao: UserDao
    ): ConnectionManager = ConnectionManager(preferencesManager, userDao)

    @Provides
    @Singleton
    fun providePresenceManager(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager,
        userDao: UserDao,
        connectionManager: ConnectionManager
    ): PresenceManager = PresenceManager(context, preferencesManager, userDao, connectionManager)

    @Provides
    @Singleton
    fun provideWebRtcManager(
        @ApplicationContext context: Context
    ): WebRtcManager = WebRtcManager(context)

    @Provides
    @Singleton
    fun provideFileTransferManager(
        chatTransferManager: ChatTransferManager,
        liveTransferManager: LiveTransferManager,
        fileTransferRepository: com.ipphone.domain.repository.FileTransferRepository
    ): FileTransferManager = FileTransferManager(
        chatTransferManager,
        liveTransferManager,
        fileTransferRepository
    )

    @Provides
    @Singleton
    fun provideCallManager(
        @ApplicationContext context: Context,
        connectionManager: ConnectionManager,
        callRepository: com.ipphone.domain.repository.CallRepository,
        userRepository: com.ipphone.domain.repository.UserRepository,
        preferencesManager: PreferencesManager
    ): CallManager = CallManager(
        context,
        connectionManager,
        callRepository,
        userRepository,
        preferencesManager
    )

    @Provides
    @Singleton
    fun provideSyncManager(
        syncQueueDao: SyncQueueDao,
        presenceManager: PresenceManager,
        connectionManager: ConnectionManager,
        messageRepository: com.ipphone.domain.repository.MessageRepository,
        fileTransferRepository: com.ipphone.domain.repository.FileTransferRepository,
        preferencesManager: PreferencesManager,
        @ApplicationContext context: Context
    ): SyncManager = SyncManager(
        syncQueueDao           = syncQueueDao,
        presenceManager        = presenceManager,
        connectionManager      = connectionManager,
        messageRepository      = messageRepository,
        fileTransferRepository = fileTransferRepository,
        preferencesManager     = preferencesManager,
        context                = context
    )

    @Provides
    @Singleton
    fun provideMessagingEngine(
        @ApplicationContext context: Context,
        connectionManager: ConnectionManager,
        presenceManager: PresenceManager,
        messageRepository: com.ipphone.domain.repository.MessageRepository,
        userRepository: com.ipphone.domain.repository.UserRepository,
        syncManager: SyncManager,
        preferencesManager: PreferencesManager,
        notificationHelper: NotificationHelper,
        fileTransferManager: FileTransferManager
    ): MessagingEngine = MessagingEngine(
        context             = context,
        connectionManager   = connectionManager,
        presenceManager     = presenceManager,
        messageRepository   = messageRepository,
        userRepository      = userRepository,
        syncManager         = syncManager,
        preferencesManager  = preferencesManager,
        notificationHelper  = notificationHelper,
        fileTransferManager = fileTransferManager
    )
}