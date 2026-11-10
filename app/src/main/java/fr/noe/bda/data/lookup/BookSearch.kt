package fr.noe.bda.data.lookup

/**
 * Finds editions by title (or author) online. A hit only carries what the result list shows:
 * picking one looks its ISBN up through [BookLookup] for the full record.
 */
interface BookSearch {

    suspend fun searchByTitle(query: String): SearchResult
}

sealed interface SearchResult {

    /** Never empty. */
    data class Found(val hits: List<SearchHit>) : SearchResult

    /** Every source answered and none has an edition with an ISBN for this query. */
    data object NotFound : SearchResult

    /** Nothing found, and at least one source could not be reached: worth a retry. */
    data object Unavailable : SearchResult
}

/** One edition in the result list. Results without an ISBN are dropped: a book is keyed by it. */
data class SearchHit(
    val isbn13: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val publishedDate: String? = null,
    val coverUrl: String? = null,
)

/**
 * One search API. Returns an empty list when nothing matches, and throws
 * (`IOException`, `HttpException`, `SerializationException`) when it couldn't answer.
 */
fun interface TitleSearchSource {
    suspend fun searchByTitle(query: String): List<SearchHit>
}
