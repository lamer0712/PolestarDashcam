package com.polestar.dashcamexporter

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Local proxy-style cache matching the OEM Gallery playback architecture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackCache {
    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "dvr-playback-cache"),
            LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { cache = it }
    }

    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val openEndedUpstream = ResolvingDataSource.Factory(upstream) { spec ->
            // The DVR rejects bounded ranges with HTTP 403. OEM Gallery sends
            // only bytes=offset- for every cache miss and resume request.
            spec.buildUpon().setLength(androidx.media3.common.C.LENGTH_UNSET.toLong()).build()
        }
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(openEndedUpstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
