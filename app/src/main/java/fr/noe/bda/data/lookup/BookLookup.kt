package fr.noe.bda.data.lookup

/** Finds a book's metadata online. The add flow's only way to reach the book APIs. */
interface BookLookup {

    /** [isbn13] must already be normalised by `Isbn.toIsbn13`. */
    suspend fun findByIsbn(isbn13: String): LookupResult
}

sealed interface LookupResult {

    data class Found(val metadata: BookMetadata) : LookupResult

    /** Every source answered and none knows this ISBN: offer the manual form. */
    data object NotFound : LookupResult

    /** Nothing found, and at least one source could not be reached: worth a retry. */
    data object Unavailable : LookupResult
}

/**
 * One metadata API. Returns `null` when the API doesn't know the ISBN, and throws
 * (`IOException`, `HttpException`, `SerializationException`) when it couldn't answer.
 */
fun interface BookSource {
    suspend fun findByIsbn(isbn13: String): BookMetadata?
}
