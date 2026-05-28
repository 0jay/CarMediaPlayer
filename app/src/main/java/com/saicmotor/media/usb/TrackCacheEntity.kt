package com.saicmotor.media.usb

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted representation of a single audio track's tag data.
 *
 * [lastModified] mirrors the filesystem's last-modified timestamp for the
 * file at [path].  The cache entry is considered valid as long as this value
 * matches the file on disk — no hash or content comparison is needed.
 */
@Entity(tableName = "track_cache")
data class TrackCacheEntity(
    @PrimaryKey val path:         String,
    val lastModified:             Long,
    val title:                    String,
    val artist:                   String,
    val album:                    String,
    val trackNumber:              Int,
    val durationMs:               Long,
    val artUri:                   String?   // serialised Uri, or null
)
