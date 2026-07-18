package com.foqos.android.di

import android.content.Context
import androidx.room.Room
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.db.dao.BlockedProfileDao
import com.foqos.android.data.db.dao.BlockedProfileSessionDao
import com.foqos.android.data.db.dao.SoftUnblockDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "foqos.db").build()

    @Provides
    fun provideBlockedProfileDao(database: AppDatabase): BlockedProfileDao =
        database.blockedProfileDao()

    @Provides
    fun provideBlockedProfileSessionDao(database: AppDatabase): BlockedProfileSessionDao =
        database.blockedProfileSessionDao()

    @Provides
    fun provideSoftUnblockDao(database: AppDatabase): SoftUnblockDao =
        database.softUnblockDao()
}
