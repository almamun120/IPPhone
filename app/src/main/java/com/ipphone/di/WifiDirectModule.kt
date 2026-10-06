package com.ipphone.di

import android.content.Context
import com.ipphone.network.wifidirect.WifiDirectManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WifiDirectModule {

    @Provides
    @Singleton
    fun provideWifiDirectManager(
        @ApplicationContext context: Context
    ): WifiDirectManager = WifiDirectManager(context)
}
