package com.kschulz.aro.di

import com.kschulz.aro.data.sync.ProductionTimeSource
import com.kschulz.aro.data.sync.TimeSource
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
