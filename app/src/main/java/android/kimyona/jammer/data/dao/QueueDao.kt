package android.kimyona.jammer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import android.kimyona.jammer.data.entity.QueueItem

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY position ASC")
    fun getAll(): LiveData<List<QueueItem>>

    @Query("SELECT * FROM queue WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrent(): QueueItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItem>)

    @Query("DELETE FROM queue")
    suspend fun clear()

    @Query("UPDATE queue SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE queue SET isCurrent = 1 WHERE id = :id")
    suspend fun setCurrent(id: Long)
}
