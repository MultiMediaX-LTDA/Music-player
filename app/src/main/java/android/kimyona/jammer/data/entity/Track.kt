package android.kimyona.jammer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val dateAdded: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    // Multi-artist support: stored as "Artist A; Artist B"
    val artistsJoined: String? = null,
    val genresJoined: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val lyricsPath: String? = null,
    val coverPath: String? = null
)
