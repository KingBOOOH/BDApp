package fr.noe.bda.data.lookup

import fr.noe.bda.data.lookup.googlebooks.GoogleBooksApi
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksVolume
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksVolumes
import fr.noe.bda.data.lookup.openlibrary.OpenLibraryApi
import fr.noe.bda.data.lookup.openlibrary.OpenLibraryBookData
import fr.noe.bda.data.lookup.openlibrary.OpenLibraryEdition
import fr.noe.bda.data.lookup.openlibrary.OpenLibrarySearch
import fr.noe.bda.di.LookupModule
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Responses saved under `src/test/resources/lookup/`, decoded with the app's own [Json].
 *
 * The OpenLibrary files and `googlebooks-volume-live-…` are real responses saved on
 * 22 Oct 2026. The other Google Books volume is the example response in the API's
 * documentation, and the search file is that volume as a search hit, without `dimensions`:
 * the live search endpoint was returning 503s and empty results that day.
 * `openlibrary-search-le-petit-prince.json` is a real `q=le petit prince&lang=fr` search,
 * saved on 23 Oct 2026; Google Books search was again answering 503 and 429.
 */
internal object Fixtures {

    const val HOBBIT = "9780547928227"          // OpenLibrary: no authors on the edition, no dimensions
    const val PETIT_PRINCE = "9782070612758"    // OpenLibrary: dimensions in inches
    const val GOOGLE_STORY = "9780553804577"    // Both sources

    val json: Json = LookupModule.provideJson()

    fun text(name: String): String =
        checkNotNull(javaClass.getResource("/lookup/$name")) { "missing fixture $name" }.readText()

    inline fun <reified T> decode(name: String): T = json.decodeFromString(text(name))
}

/**
 * Serves the saved OpenLibrary responses; any other ISBN gets the API's 404 and empty map.
 * Every search gets the "le petit prince" results.
 */
internal class FixtureOpenLibraryApi : OpenLibraryApi {

    val searches = mutableListOf<String>()

    override suspend fun search(query: String, lang: String?, fields: String, limit: Int): OpenLibrarySearch {
        searches += "$query lang=$lang"
        return Fixtures.decode("openlibrary-search-le-petit-prince.json")
    }

    override suspend fun edition(isbn13: String): Response<OpenLibraryEdition> =
        if (javaClass.getResource("/lookup/openlibrary-edition-$isbn13.json") != null) {
            Response.success(Fixtures.decode("openlibrary-edition-$isbn13.json"))
        } else {
            Response.error(404, "".toResponseBody())
        }

    override suspend fun books(bibkeys: String): Map<String, OpenLibraryBookData> {
        val isbn13 = bibkeys.removePrefix("ISBN:")
        return if (javaClass.getResource("/lookup/openlibrary-data-$isbn13.json") != null) {
            Fixtures.decode("openlibrary-data-$isbn13.json")
        } else {
            emptyMap()
        }
    }
}

/** Serves the saved Google Books responses whatever the query, and records each call. */
internal class FixtureGoogleBooksApi : GoogleBooksApi {

    val calls = mutableListOf<String>()

    override suspend fun search(query: String, key: String?): GoogleBooksVolumes {
        calls += "search $query key=$key"
        return Fixtures.decode("googlebooks-search-${Fixtures.GOOGLE_STORY}.json")
    }

    override suspend fun volume(id: String, key: String?): GoogleBooksVolume {
        calls += "volume $id key=$key"
        return Fixtures.decode("googlebooks-volume-${Fixtures.GOOGLE_STORY}.json")
    }
}
