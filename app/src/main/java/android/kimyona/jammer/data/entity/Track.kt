package android.kimyona.jammer.data.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * Track — v2 (KSP-safe)
 *
 * Mudança vs anterior: default values de contentRating e releaseType usam
 * strings literais em vez de referenciar ContentRating/ReleaseType no construtor.
 * Isso evita erro KSP "MissingType: references a type that is not present"
 * quando o Room processa a entidade antes de resolver os enums.
 */
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
    // Multi-artist: stored as "Artist A; Artist B"
    val artistsJoined: String? = null,
    val genresJoined: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val lyricsPath: String? = null,
    val coverPath: String? = null,
    // --- v2 additions ---
    /** Album artist (used for compilations / Various Artists). */
    val albumArtist: String? = null,
    /**
     * Alternate/romanized search name.
     * Example: a track where artist = "椎名林檎" can have alias = "Shiina Ringo"
     * so searching either form finds the track.
     */
    val alias: String? = null,
    /**
     * Content rating, stored as string name.
     * Default "NONE" (clean/unrated).
     * NÃO usar ContentRating.NONE.name aqui — causa erro KSP.
     */
    val contentRating: String = "NONE",
    /**
     * Release type, stored as string name.
     * null = not yet tagged.
     */
    val releaseType: String? = null
) {
    /** Typed content rating — safe to use anywhere without string comparison. */
    @get:Ignore
    val contentRatingEnum: ContentRating
        get() = ContentRating.fromString(contentRating)

    /** Typed release type — null if unknown/unset. */
    @get:Ignore
    val releaseTypeEnum: ReleaseType?
        get() = ReleaseType.fromString(releaseType)

    /**
     * All artists as an ordered list.
     * Prefers [artistsJoined] (multi-artist) over the plain [artist] field.
     */
    @get:Ignore
    val artistList: List<String>
        get() = artistsJoined
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf(artist)

    /**
     * Display-ready artist string — joins multiple artists with " · ".
     */
    @get:Ignore
    val displayArtist: String
        get() = artistList.joinToString(" · ")
}
