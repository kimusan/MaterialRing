package com.kschulz.aro.di

import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.HealthStore
import com.kschulz.aro.domain.BodyBattery
import com.kschulz.aro.domain.BodyBatteryUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    // SyncEngine depends on the store seam, not the concrete repository.
    @Provides
    @Singleton
    fun provideHealthStore(impl: HealthRepository): HealthStore = impl

    // SyncEngine depends on the body-battery seam, not the concrete use case.
    @Provides
    @Singleton
    fun provideBodyBattery(impl: BodyBatteryUseCase): BodyBattery = impl
}
