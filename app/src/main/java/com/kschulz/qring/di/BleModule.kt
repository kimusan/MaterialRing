package com.kschulz.qring.di

import com.kschulz.qring.data.ble.RingCommand
import com.kschulz.qring.data.ble.RingCommandClient
import com.kschulz.qring.data.ble.RingProtocolCodec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    @Provides
    @Singleton
    fun provideRingProtocolCodec(): RingProtocolCodec = RingProtocolCodec()

    // The rest of the app depends on the seam, not the client.
    @Provides
    @Singleton
    fun provideRingCommand(impl: RingCommandClient): RingCommand = impl
}
