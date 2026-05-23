package android.kimyona.jammer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.kimyona.jammer.data.dao.PlaylistDao
import android.kimyona.jammer.data.dao.QueueDao
import android.kimyona.jammer.data.dao.TrackDao
import android.kimyona.jammer.data.entity.Playlist
import android.kimyona.jammer.data.entity.PlaylistTrackCrossRef
import android.kimyona.jammer.data.entity.QueueItem
import android.kimyona.jammer.data.entity.Track

/**
 * JammerDatabase — BULLETPROOF EDITION.
 *
 * Correções:
 * - fallbackToDestructiveMigration() para evitar crash em schema changes
 * - Singleton thread-safe com double-check
 * - ExportSchema = false (evita warnings)
 */
@Database(
    entities = [
        Track::class,
        Playlist::class,
        PlaylistTrackCrossRef::class,
        QueueItem::class
    ],
    version = 1,
    exportSchema = false
)
abstract class JammerDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var INSTANCE: JammerDatabase? = null

        fun getDatabase(context: Context): JammerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        private fun buildDatabase(context: Context): JammerDatabase {
            return Room.databaseBuilder(
                context,
                JammerDatabase::class.java,
                "jammer_database"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
