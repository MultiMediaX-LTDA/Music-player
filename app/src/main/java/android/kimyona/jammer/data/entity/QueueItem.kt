package android.kimyona.jammer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queue",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["path"],
            childColumns = ["trackPath"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trackPath"])]
)
data class QueueItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackPath: String,
    val position: Int,
    val isCurrent: Boolean = false
)
