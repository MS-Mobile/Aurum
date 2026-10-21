package com.msmobile.aurum.di

import com.msmobile.aurum.util.DateTimeProvider
import com.msmobile.aurum.util.AndroidLogger
import com.msmobile.aurum.util.DispatcherProvider
import com.msmobile.aurum.util.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class ApplicationModule {
    @Singleton
    @Provides
    fun dispatcherProvider(): DispatcherProvider = DispatcherProvider()

    @Singleton
    @Provides
    fun dateTimeProvider(): DateTimeProvider = DateTimeProvider()

    @Singleton
    @Provides
    fun logger(): Logger = AndroidLogger()
}
