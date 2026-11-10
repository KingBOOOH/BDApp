package fr.noe.bda.data.lookup.googlebooks

import fr.noe.bda.data.lookup.MetadataSource
import fr.noe.bda.data.book.Isbn
import fr.noe.bda.data.lookup.BookMetadata
import fr.noe.bda.data.lookup.BookSource
import fr.noe.bda.data.lookup.SearchHit
import fr.noe.bda.data.lookup.TitleSearchSource
import javax.inject.Inject
import javax.inject.Qualifier

/** The Google Books API key from `local.properties`; blank when none is set. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleBooksApiKey

class GoogleBooksSource @Inject constructor(
    private val api: GoogleBooksApi,
    @GoogleBooksApiKey apiKey: String,
) : BookSource, TitleSearchSource {

    private val key = apiKey.ifBlank { null }

    /**
     * Two requests: the search finds the volume id, then the volume itself carries the
     * dimensions the search results leave out.
     */
    override suspend fun findByIsbn(isbn13: String): BookMetadata? {
        val match = api.search("isbn:$isbn13", key).items
            .firstOrNull { isbn13 in it.volumeInfo.isbn13s() }
            ?: return null
        return api.volume(match.id, key).volumeInfo.toMetadata(isbn13)
    }

    override suspend fun searchByTitle(query: String): List<SearchHit> =
        api.search("intitle:$query", key).items.mapNotNull { it.volumeInfo.toSearchHit() }.distinctBy { it.isbn13 }
}

/** `null` for a volume without a title or an ISBN, such as a magazine issue. */
internal fun GoogleBooksVolumeInfo.toSearchHit(): SearchHit? {
    val title = title?.takeIf { it.isNotBlank() } ?: return null
    // A volume's ISBN-10 and ISBN-13 normalise to the same key.
    val isbn13 = isbn13s().firstOrNull() ?: return null
    return SearchHit(
        isbn13 = isbn13,
        title = title,
        authors = authors,
        publishedDate = publishedDate,
        coverUrl = imageLinks?.run { thumbnail ?: smallThumbnail }?.replaceFirst("http://", "https://"),
    )
}

/** A search on `isbn:` can return other editions, so a volume only counts if it lists the ISBN. */
private fun GoogleBooksVolumeInfo.isbn13s(): Set<String> =
    industryIdentifiers
        .filter { it.type == "ISBN_13" || it.type == "ISBN_10" }
        .mapNotNull { Isbn.toIsbn13(it.identifier) }
        .toSet()

/** `null` when the volume has no title, which a shelf can't show. */
internal fun GoogleBooksVolumeInfo.toMetadata(isbn13: String): BookMetadata? {
    val title = title?.takeIf { it.isNotBlank() } ?: return null
    return BookMetadata(
        isbn13 = isbn13,
        title = title,
        source = MetadataSource.GOOGLE_BOOKS,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        authors = authors,
        publisher = publisher,
        publishedDate = publishedDate,
        language = language,
        pageCount = pageCount?.takeIf { it > 0 },
        heightCm = dimensions?.height?.let(::parseCm),
        thicknessCm = dimensions?.thickness?.let(::parseCm),
        // Android blocks cleartext HTTP, and Google Books often links covers over http://
        coverUrl = imageLinks?.run { large ?: medium ?: small ?: thumbnail ?: smallThumbnail }
            ?.replaceFirst("http://", "https://"),
    )
}

private fun parseCm(text: String): Float? = CENTIMETRES.matchEntire(text.trim())?.groupValues?.get(1)?.toFloatOrNull()

private val CENTIMETRES = Regex("""([0-9.]+) *cm""")
