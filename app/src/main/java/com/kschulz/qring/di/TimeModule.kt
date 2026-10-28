package com.kschulz.qring.di

import com.kschulz.qring.data.sync.ProductionTimeSource
import com.kschulz.qring.data.sync.TimeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = ProductionTimeSource()
}
