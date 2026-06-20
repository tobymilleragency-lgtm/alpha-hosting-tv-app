package com.alphahostingtv.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AlphaHostingTvApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var deviceMac: com.alphahostingtv.tv.data.config.DeviceMac
    @Inject lateinit var prefsStore: com.alphahostingtv.tv.data.prefs.UserPreferencesStore

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(true)
        .build()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        val pkg = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        RemoteLog.init(
            ctx = this,
            mac = deviceMac.mac,
            versionName = pkg.versionName ?: "",
            versionCode = pkg.versionCode,
        )
        RemoteLog.info("app", "onCreate")

        bgScope.launch {
            prefsStore.flow.collect { p ->
                RemoteLog.telemetryEnabled = p.telemetryEnabled
                com.alphahostingtv.tv.ui.common.EpgClock.offsetMinutes = p.epgTimeOffsetMin
                com.alphahostingtv.tv.data.repo.LocalLogos.treeUri = p.localLogosFolderUri
            }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            RemoteLog.crashSync(t, e)
            previous?.uncaughtException(t, e)
        }
    }

    override fun onTerminate() {
        RemoteLog.info("app", "onTerminate")
        super.onTerminate()
    }
}
