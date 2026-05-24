package android.kimyona.jammer.data.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * Track — v2
 *
 * New fields vs v1:
 *   albumArtist   — for compilations and multi-artist albums
 *   alias         — romanized / alternate search name (e.g., "Utada Hikaru" → "宇多田ヒカル")
 *   contentRating — stored as ContentRating.name; use contentRatingEnum for the typed value
 *   releaseType   — stored as ReleaseType.name; null = unknown
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
     * Content rating, stored as [ContentRating.name].
     * Default NONE (clean/unrated).
     */
    val contentRating: String = ContentRating.NONE.name,
    /**
     * Release type, stored as [ReleaseType.name].
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
