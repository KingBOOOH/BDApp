package fr.noe.bda.data.lookup

import fr.noe.bda.data.book.BookFormat

/**
 * What a lookup knows about one edition. Every source maps into this model, so a field
 * missing from one source can be filled by another; it becomes a `BookEntity` once the
 * spine is computed.
 */
data class BookMetadata(
    val isbn13: String,
    val title: String,
    val source: MetadataSource,
    /** OpenLibrary work id such as `OL27482W`, shared by every edition of the work. */
    val workKey: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val publishedDate: String? = null,
    /** As the source gives it: OpenLibrary uses MARC codes (`eng`, `fre`), Google Books ISO 639-1 (`en`, `fr`). */
    val language: String? = null,
    val pageCount: Int? = null,
    val heightCm: Float? = null,
    val thicknessCm: Float? = null,
    /** Only OpenLibrary knows it; it gives the spine a likely height when [heightCm] is missing. */
    val format: BookFormat? = null,
    val coverUrl: String? = null,
) {

    /**
     * True when the fallback has nothing to add. Subtitle and work key are left out: most
     * books have no subtitle, and Google Books never has a work key.
     */
    val isComplete: Boolean
        get() = authors.isNotEmpty() && publisher != null && publishedDate != null &&
            language != null && pageCount != null && heightCm != null && thicknessCm != null &&
            coverUrl != null

    /** This record, with its empty fields taken from [other]. Where both have a value, this one wins. */
    fun filledFrom(other: BookMetadata?): BookMetadata {
        if (other == null) return this
        return copy(
            workKey = workKey ?: other.workKey,
            subtitle = subtitle ?: other.subtitle,
            authors = authors.ifEmpty { other.authors },
            publisher = publisher ?: other.publisher,
            publishedDate = publishedDate ?: other.publishedDate,
            language = language ?: other.language,
            pageCount = pageCount ?: other.pageCount,
            heightCm = heightCm ?: other.heightCm,
            thicknessCm = thicknessCm ?: other.thicknessCm,
            format = format ?: other.format,
            coverUrl = coverUrl ?: other.coverUrl,
        )
    }
}

enum class MetadataSource { OPEN_LIBRARY, GOOGLE_BOOKS, MANUAL }
