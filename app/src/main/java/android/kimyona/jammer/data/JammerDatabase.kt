package android.kimyona.jammer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.kimyona.jammer.data.dao.*
import android.kimyona.jammer.data.entity.*

@Database(
    entities = [
        Track::class,
        QueueItem::class,
        Playlist::class,
        PlaylistTrackCrossRef::class
    ],
    version = 1,
    exportSchema = false
)
abstract class JammerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun queueDao(): QueueDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: JammerDatabase? = null

        fun getDatabase(context: Context): JammerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    JammerDatabase::class.java,
                    "jammer_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
