package fr.noe.bda.data.lookup.openlibrary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** https://openlibrary.org/developers/api. Base URL `https://openlibrary.org/`. */
interface OpenLibraryApi {

    /** The edition record. 404 when the ISBN is unknown; otherwise a redirect to `/books/OL…M.json`. */
    @GET("isbn/{isbn}.json")
    suspend fun edition(@Path("isbn") isbn13: String): Response<OpenLibraryEdition>

    /**
     * The same edition with author names resolved, keyed by [bibkeys] (`ISBN:978…`); an
     * empty map when unknown. The edition record alone isn't enough: its `authors` holds
     * only author ids, and some editions leave it out entirely.
     */
    @GET("api/books?format=json&jscmd=data")
    suspend fun books(@Query("bibkeys") bibkeys: String): Map<String, OpenLibraryBookData>

    /**
     * Works matching [query] (title or author), each with the one edition that best matches
     * it. [lang] (ISO 639-1, e.g. `fr`) makes that edition one in this language when the work
     * has one; without it a French title can come back as its Spanish translation.
     */
    @GET("search.json")
    suspend fun search(
        @Query("q") query: String,
        @Query("lang") lang: String?,
        @Query("fields") fields: String = SEARCH_FIELDS,
        @Query("limit") limit: Int = 20,
    ): OpenLibrarySearch
}

/** Only what a result row shows; the full record comes from [OpenLibraryApi.edition] once picked. */
internal const val SEARCH_FIELDS =
    "key,title,author_name,cover_i,editions,editions.title,editions.isbn,editions.cover_i,editions.publish_date"

@Serializable
data class OpenLibraryEdition(
    val title: String? = null,
    val subtitle: String? = null,
    val publishers: List<String> = emptyList(),
    @SerialName("publish_date") val publishDate: String? = null,
    @SerialName("number_of_pages") val numberOfPages: Int? = null,
    /** `"height x width x depth units"`, e.g. `"6.9 x 4.9 x 0.3 inches"`. */
    @SerialName("physical_dimensions") val physicalDimensions: String? = null,
    /** Free text, e.g. `"Paperback"`, `"Mass Market Paperback"`, `"Hardcover"`. */
    @SerialName("physical_format") val physicalFormat: String? = null,
    val covers: List<Long> = emptyList(),
    val languages: List<OpenLibraryKey> = emptyList(),
    val works: List<OpenLibraryKey> = emptyList(),
)

/** A reference to another record, e.g. `{"key": "/works/OL27482W"}`. */
@Serializable
data class OpenLibraryKey(val key: String)

@Serializable
data class OpenLibraryBookData(
    val authors: List<OpenLibraryAuthor> = emptyList(),
    val cover: OpenLibraryCover? = null,
)

@Serializable
data class OpenLibraryAuthor(val name: String)

@Serializable
data class OpenLibraryCover(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
data class OpenLibrarySearch(val docs: List<OpenLibrarySearchWork> = emptyList())

@Serializable
data class OpenLibrarySearchWork(
    val title: String? = null,
    @SerialName("author_name") val authorName: List<String> = emptyList(),
    @SerialName("cover_i") val coverId: Long? = null,
    val editions: OpenLibrarySearchEditions = OpenLibrarySearchEditions(),
)

@Serializable
data class OpenLibrarySearchEditions(val docs: List<OpenLibrarySearchEdition> = emptyList())

/** Search results give `publish_date` as a list, e.g. `["1976"]`. */
@Serializable
data class OpenLibrarySearchEdition(
    val title: String? = null,
    val isbn: List<String> = emptyList(),
    @SerialName("cover_i") val coverId: Long? = null,
    @SerialName("publish_date") val publishDate: List<String> = emptyList(),
)
