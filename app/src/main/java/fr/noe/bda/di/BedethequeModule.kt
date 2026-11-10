package fr.noe.bda.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.noe.bda.data.bedetheque.BedethequeClient
import fr.noe.bda.data.bedetheque.BedethequeSource
import fr.noe.bda.data.bedetheque.JsoupBedethequeSource
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BedethequeModule {

    @Binds
    abstract fun bindBedethequeSource(source: JsoupBedethequeSource): BedethequeSource

    companion object {
        /** Singleton so the throttle, cookies and "blocked" state cover every caller. */
        @Provides
        @Singleton
        fun provideBedethequeClient(client: OkHttpClient): BedethequeClient = BedethequeClient(client)
    }
}
