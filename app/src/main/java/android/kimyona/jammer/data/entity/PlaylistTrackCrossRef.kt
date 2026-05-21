package android.kimyona.jammer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackPath"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Track::class,
            parentColumns = ["path"],
            childColumns = ["trackPath"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["trackPath"]),
        Index(value = ["playlistId"])
    ]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackPath: String,
    val position: Int
)
