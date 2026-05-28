package com.saicmotor.media.usb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackCacheDao {

    /** Load all cached entries.  Called once per scan to build the diff map. */
    @Query("SELECT * FROM track_cache")
    fun getAll(): List<TrackCacheEntity>

    /**
     * Insert or replace entries.  REPLACE handles the case where a file's
     * tags changed (same path, different lastModified or content).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(tracks: List<TrackCacheEntity>)

    /** Remove entries whose files no longer exist on the drive. */
    @Query("DELETE FROM track_cache WHERE path IN (:paths)")
    fun deleteByPaths(paths: List<String>)
}
