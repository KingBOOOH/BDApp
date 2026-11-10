package fr.noe.bda.data.lookup

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Asks [primary] (OpenLibrary) first. [fallback] (Google Books) is only asked when the
 * primary found nothing or left fields empty; its answer fills those fields and never
 * overrides the primary's.
 */
class DefaultBookLookup(
    private val primary: BookSource,
    private val fallback: BookSource,
) : BookLookup {

    override suspend fun findByIsbn(isbn13: String): LookupResult {
        val fromPrimary = primary.tryFind(isbn13)
        val primaryBook = fromPrimary.getOrNull()
        if (primaryBook != null && primaryBook.isComplete) return LookupResult.Found(primaryBook)

        val fromFallback = fallback.tryFind(isbn13)
        val book = primaryBook?.filledFrom(fromFallback.getOrNull()) ?: fromFallback.getOrNull()
        return when {
            book != null -> LookupResult.Found(book)
            fromPrimary.isFailure || fromFallback.isFailure -> LookupResult.Unavailable
            else -> LookupResult.NotFound
        }
    }

    /** Network and parsing failures become a failed [Result]; cancellation still propagates. */
    private suspend fun BookSource.tryFind(isbn13: String): Result<BookMetadata?> =
        try {
            Result.success(findByIsbn(isbn13))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: HttpException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(e)
        }
}
