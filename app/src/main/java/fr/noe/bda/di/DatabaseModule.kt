package fr.noe.bda.di

import android.content.Context
import androidx.room3.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.BdaDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BdaDatabase =
        Room.databaseBuilder(context, BdaDatabase::class.java, "bda.db").build()

    @Provides
    fun provideDao(database: BdaDatabase): BdaDao = database.dao()
}
