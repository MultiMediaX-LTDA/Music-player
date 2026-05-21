package android.kimyona.jammer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import android.kimyona.jammer.data.entity.Playlist
import android.kimyona.jammer.data.entity.PlaylistTrackCrossRef
import android.kimyona.jammer.data.entity.Track

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): LiveData<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: Playlist): Long

    @Delete
    suspend fun delete(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrack(crossRef: PlaylistTrackCrossRef)

    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.path = pt.trackPath
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
    """)
    fun getTracks(playlistId: Long): LiveData<List<Track>>
}
