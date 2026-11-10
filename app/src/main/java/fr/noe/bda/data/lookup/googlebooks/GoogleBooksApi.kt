package fr.noe.bda.data.lookup.googlebooks

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * https://developers.google.com/books/docs/v1/using. Base URL `https://www.googleapis.com/books/v1/`.
 * Requests without an API key share an anonymous quota that is often exhausted (HTTP 429),
 * so [key] should be set; Retrofit drops the parameter when it is `null`.
 */
interface GoogleBooksApi {

    /** Search results leave out `dimensions`; [volume] has them. */
    @GET("volumes")
    suspend fun search(@Query("q") query: String, @Query("key") key: String?): GoogleBooksVolumes

    @GET("volumes/{id}")
    suspend fun volume(@Path("id") id: String, @Query("key") key: String?): GoogleBooksVolume
}

/** `items` is absent when nothing matches. */
@Serializable
data class GoogleBooksVolumes(val items: List<GoogleBooksVolume> = emptyList())

@Serializable
data class GoogleBooksVolume(
    val id: String,
    val volumeInfo: GoogleBooksVolumeInfo = GoogleBooksVolumeInfo(),
)

@Serializable
data class GoogleBooksVolumeInfo(
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val publishedDate: String? = null,
    val industryIdentifiers: List<GoogleBooksIdentifier> = emptyList(),
    val pageCount: Int? = null,
    val dimensions: GoogleBooksDimensions? = null,
    val imageLinks: GoogleBooksImageLinks? = null,
    val language: String? = null,
)

/** [type] is `ISBN_10`, `ISBN_13` or `OTHER`. */
@Serializable
data class GoogleBooksIdentifier(val type: String, val identifier: String)

/** Strings in centimetres, e.g. `"24.00 cm"`. */
@Serializable
data class GoogleBooksDimensions(
    val height: String? = null,
    val width: String? = null,
    val thickness: String? = null,
)

@Serializable
data class GoogleBooksImageLinks(
    val smallThumbnail: String? = null,
    val thumbnail: String? = null,
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
)
