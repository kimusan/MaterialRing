package com.kschulz.qring

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class QRingApplication : Application(), Configuration.Provider {

    // Hilt builds the worker factory (its constructor is package-private); inject it.
    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    // WorkManager instantiates @HiltWorker classes through this factory.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            .build()
}
