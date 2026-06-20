package com.alphahostingtv.tv.di

import android.content.Context
import androidx.room.Room
import com.alphahostingtv.tv.data.db.CategoryDao
import com.alphahostingtv.tv.data.db.ChannelDao
import com.alphahostingtv.tv.data.db.ProviderDao
import com.alphahostingtv.tv.data.db.AlphaHostingTvDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AlphaHostingTvDb =
        Room.databaseBuilder(ctx, AlphaHostingTvDb::class.java, "ultra-tv.db")
            // Versions 1..9 predate schema export; there are no Migration objects
            // for them, so we wipe-and-rebuild when upgrading from any of them.
            // FUTURE: any bump past 10 MUST ship an explicit Migration and be
            // registered here via .addMigrations(MIGRATION_10_11, ...). Do NOT
            // widen this destructive range — the exported schemas under
            // app/schemas let Room auto-generate / verify those migrations.
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8, 9)
            .build()

    @Provides fun provideProviderDao(db: AlphaHostingTvDb): ProviderDao = db.providerDao()
    @Provides fun provideChannelDao(db: AlphaHostingTvDb): ChannelDao = db.channelDao()
    @Provides fun provideMovieDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.MovieDao = db.movieDao()
    @Provides fun provideSeriesDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.SeriesDao = db.seriesDao()
    @Provides fun provideEpisodeDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.EpisodeDao = db.episodeDao()
    @Provides fun provideCategoryDao(db: AlphaHostingTvDb): CategoryDao = db.categoryDao()
    @Provides fun provideFavoriteDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.FavoriteDao = db.favoriteDao()
    @Provides fun provideEpgDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.EpgDao = db.epgDao()
    @Provides fun provideWatchHistoryDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.WatchHistoryDao = db.watchHistoryDao()
    @Provides fun provideRecordingDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.db.RecordingDao = db.recordingDao()
    @Provides fun provideReminderDao(db: AlphaHostingTvDb): com.alphahostingtv.tv.data.reminders.ReminderDao = db.reminderDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
