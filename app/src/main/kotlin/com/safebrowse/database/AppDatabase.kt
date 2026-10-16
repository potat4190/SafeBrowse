package com.safebrowse.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.safebrowse.database.dao.BlockedDomainDao
import com.safebrowse.database.entity.BlockedDomain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [BlockedDomain::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedDomainDao(): BlockedDomainDao
    companion object { const val DATABASE_NAME = "safebrowse.db" }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()

    @Provides
    fun provideDao(db: AppDatabase): BlockedDomainDao = db.blockedDomainDao()
}
