package com.saicmotor.media.usb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema versions:
 *   1 — initial.
 *   2 — drop cache to re-run readTrack() with the new mojibake-repair pass.
 *   3 — drop cache for new typography normalisation pass.
 *   4 — drop cache: the earlier mojibake repair used ISO-8859-1 for the
 *       round-trip, but MediaMetadataRetriever actually decodes those tags
 *       via Windows-1252, so characters in the 0x80–0x9F range (`€`, `™`,
 *       etc.) couldn't be encoded back to their original bytes and the
 *       round-trip silently no-op'd.  Fixed to CP1252 in code; bump version
 *       so existing cached titles are re-read and the fix actually applies.
 *
 * The cache can always be rebuilt from the filesystem, so a destructive
 * migration on version bump is safe and is what we want here.
 */
@Database(
    entities    = [TrackCacheEntity::class],
    version     = 4,
    exportSchema = false
)
abstract class TrackCacheDatabase : RoomDatabase() {

    abstract fun trackCacheDao(): TrackCacheDao

    companion object {
        @Volatile private var INSTANCE: TrackCacheDatabase? = null

        fun getInstance(context: Context): TrackCacheDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrackCacheDatabase::class.java,
                    "track_cache.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
