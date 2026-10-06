package com.ipphone.di

import com.ipphone.data.repository.CallRepositoryImpl
import com.ipphone.data.repository.FileTransferRepositoryImpl
import com.ipphone.data.repository.MessageRepositoryImpl
import com.ipphone.data.repository.UserRepositoryImpl
import com.ipphone.domain.repository.CallRepository
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.domain.repository.MessageRepository
import com.ipphone.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindUserRepository(
        impl: UserRepositoryImpl
    ): UserRepository
    
    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        impl: MessageRepositoryImpl
    ): MessageRepository
    
    @Binds
    @Singleton
    abstract fun bindFileTransferRepository(
        impl: FileTransferRepositoryImpl
    ): FileTransferRepository
    
    @Binds
    @Singleton
    abstract fun bindCallRepository(
        impl: CallRepositoryImpl
    ): CallRepository
}
