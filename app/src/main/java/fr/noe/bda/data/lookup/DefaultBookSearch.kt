package fr.noe.bda.data.lookup

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Asks [primary] (OpenLibrary) first. [fallback] (Google Books) is only asked when the
 * primary found nothing or couldn't be reached; the two result lists are never mixed.
 */
class DefaultBookSearch(
    private val primary: TitleSearchSource,
    private val fallback: TitleSearchSource,
) : BookSearch {

    override suspend fun searchByTitle(query: String): SearchResult {
        val fromPrimary = primary.trySearch(query)
        fromPrimary.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return SearchResult.Found(it) }

        val fromFallback = fallback.trySearch(query)
        fromFallback.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return SearchResult.Found(it) }

        return if (fromPrimary.isFailure || fromFallback.isFailure) {
            SearchResult.Unavailable
        } else {
            SearchResult.NotFound
        }
    }

    /** Network and parsing failures become a failed [Result]; cancellation still propagates. */
    private suspend fun TitleSearchSource.trySearch(query: String): Result<List<SearchHit>> =
        try {
            Result.success(searchByTitle(query))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: HttpException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(e)
        }
}
