package android.kimyona.jammer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import android.kimyona.jammer.data.entity.Track

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun getAll(): LiveData<List<Track>>

    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun getByPath(path: String): Track?

    @Query("SELECT * FROM tracks WHERE artist LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%'")
    fun search(query: String): LiveData<List<Track>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1")
    fun getFavorites(): LiveData<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<Track>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Delete
    suspend fun delete(track: Track)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE path = :path")
    suspend fun setFavorite(path: String, favorite: Boolean)
}
