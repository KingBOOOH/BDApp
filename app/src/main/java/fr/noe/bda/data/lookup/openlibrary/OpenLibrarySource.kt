package fr.noe.bda.data.lookup.openlibrary

import fr.noe.bda.data.lookup.MetadataSource
import fr.noe.bda.data.book.Isbn
import fr.noe.bda.data.book.BookFormat
import fr.noe.bda.data.lookup.BookMetadata
import fr.noe.bda.data.lookup.BookSource
import fr.noe.bda.data.lookup.SearchHit
import fr.noe.bda.data.lookup.TitleSearchSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException
import java.util.Locale
import javax.inject.Inject

class OpenLibrarySource @Inject constructor(
    private val api: OpenLibraryApi,
) : BookSource, TitleSearchSource {

    override suspend fun findByIsbn(isbn13: String): BookMetadata? = coroutineScope {
        val bibkey = "ISBN:$isbn13"
        val data = async { api.books(bibkey)[bibkey] }
        val response = api.edition(isbn13)
        when {
            response.code() == 404 -> {
                data.cancel()
                null
            }
            !response.isSuccessful -> throw HttpException(response)
            else -> response.body()?.toMetadata(isbn13, data.await())
        }
    }

    /** Editions in the phone's language come first when a work has one. */
    override suspend fun searchByTitle(query: String): List<SearchHit> =
        api.search(query, lang = Locale.getDefault().language.ifBlank { null }).toSearchHits()
}

/** One hit per work, for its best-matching edition; works whose edition has no valid ISBN are dropped. */
internal fun OpenLibrarySearch.toSearchHits(): List<SearchHit> =
    docs.mapNotNull { work ->
        val edition = work.editions.docs.firstOrNull() ?: return@mapNotNull null
        // The list mixes ISBN-10 and ISBN-13 of the same edition; both normalise to one key.
        val isbn13 = edition.isbn.firstNotNullOfOrNull(Isbn::toIsbn13) ?: return@mapNotNull null
        // The edition's title, not the work's: it's what is printed on the copy in hand.
        val title = (edition.title ?: work.title)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SearchHit(
            isbn13 = isbn13,
            title = title,
            // Search records can list one author several times.
            authors = work.authorName.distinct(),
            publishedDate = edition.publishDate.firstOrNull(),
            coverUrl = (edition.coverId ?: work.coverId)?.takeIf { it > 0 }
                ?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
        )
    }.distinctBy { it.isbn13 }

/** `null` when the edition has no title, which a shelf can't show. */
internal fun OpenLibraryEdition.toMetadata(isbn13: String, data: OpenLibraryBookData?): BookMetadata? {
    val title = title?.takeIf { it.isNotBlank() } ?: return null
    val dimensions = physicalDimensions?.let(::parseDimensionsCm)
    return BookMetadata(
        isbn13 = isbn13,
        title = title,
        source = MetadataSource.OPEN_LIBRARY,
        workKey = works.firstOrNull()?.key?.removePrefix("/works/"),
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        authors = data?.authors.orEmpty().map { it.name },
        publisher = publishers.firstOrNull(),
        publishedDate = publishDate,
        language = languages.firstOrNull()?.key?.removePrefix("/languages/"),
        pageCount = numberOfPages?.takeIf { it > 0 },
        heightCm = dimensions?.heightCm,
        thicknessCm = dimensions?.thicknessCm,
        format = physicalFormat?.let(BookFormat::parse),
        coverUrl = data?.cover?.large ?: covers.firstOrNull { it > 0 }
            ?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
    )
}

internal data class DimensionsCm(val heightCm: Float, val thicknessCm: Float)

/**
 * Reads OpenLibrary's `"height x width x depth units"` (the format of its `UnitParser`).
 * `null` for a unit other than inches, centimetres or millimetres, or a missing number.
 */
internal fun parseDimensionsCm(text: String): DimensionsCm? {
    val match = DIMENSIONS.matchEntire(text.trim()) ?: return null
    val (height, _, depth, unit) = match.destructured
    val cmPerUnit = when (unit.lowercase().trimEnd('.')) {
        "inches", "inch", "in" -> 2.54f
        "centimeters", "centimetres", "cm" -> 1f
        "millimeters", "millimetres", "mm" -> 0.1f
        else -> return null
    }
    return DimensionsCm(
        heightCm = height.toFloatOrNull()?.times(cmPerUnit) ?: return null,
        thicknessCm = depth.toFloatOrNull()?.times(cmPerUnit) ?: return null,
    )
}

private val DIMENSIONS = Regex("""([0-9.]+) *x *([0-9.]+) *x *([0-9.]+) *(.*)""")
