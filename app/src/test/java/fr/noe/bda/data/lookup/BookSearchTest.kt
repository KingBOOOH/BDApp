package fr.noe.bda.data.lookup

import fr.noe.bda.data.lookup.googlebooks.GoogleBooksSource
import fr.noe.bda.data.lookup.openlibrary.OpenLibrarySource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Locale

class BookSearchTest {

    private val openLibraryApi = FixtureOpenLibraryApi()
    private val googleBooksApi = FixtureGoogleBooksApi()
    private val openLibrary = OpenLibrarySource(openLibraryApi)
    private val googleBooks = GoogleBooksSource(googleBooksApi, apiKey = "")

    @Test
    fun `an OpenLibrary hit is the work's best edition`() = runTest {
        val hits = openLibrary.searchByTitle("le petit prince")

        assertEquals(
            SearchHit(
                isbn13 = "9789001708528",
                title = "Le petit prince",
                authors = listOf("Antoine de Saint-Exupéry"),
                publishedDate = "1976",
                coverUrl = "https://covers.openlibrary.org/b/id/10778405-M.jpg",
            ),
            hits.first(),
        )
    }

    @Test
    fun `works whose edition has no ISBN are dropped`() = runTest {
        val hits = openLibrary.searchByTitle("le petit prince")

        // 20 works in the saved response; 3 of their editions list no ISBN.
        assertEquals(17, hits.size)
        assertTrue(hits.none { it.title == "Hoàng tu bé" })
    }

    @Test
    fun `the edition's title wins over the work's`() = runTest {
        val hits = openLibrary.searchByTitle("le petit prince")

        // The work is "Loukoum"; this edition is printed under another title.
        assertTrue(hits.any { it.isbn13 == "9782277235521" && it.title == "Le petit prince de Belleville" })
    }

    @Test
    fun `OpenLibrary is asked for editions in the phone's language`() = runTest {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            openLibrary.searchByTitle("le petit prince")
        } finally {
            Locale.setDefault(previous)
        }

        assertEquals(listOf("le petit prince lang=fr"), openLibraryApi.searches)
    }

    @Test
    fun `a Google Books hit is the volume, searched by title`() = runTest {
        val hits = googleBooks.searchByTitle("google story")

        assertEquals(listOf("search intitle:google story key=null"), googleBooksApi.calls)
        assertEquals(
            SearchHit(
                isbn13 = Fixtures.GOOGLE_STORY,
                title = "The Google story",
                authors = listOf("David A. Vise", "Mark Malseed"),
                publishedDate = "2005-11-15",
                coverUrl = "https://books.google.com/books?id=zyTCAlFPjgYC&printsec=frontcover&img=1&zoom=1&edge=curl&source=gbs_api",
            ),
            hits.single(),
        )
    }

    @Test
    fun `OpenLibrary results never ask Google Books`() = runTest {
        val search = DefaultBookSearch(openLibrary, fallback = { fail("Google Books was asked") })

        val result = search.searchByTitle("le petit prince")

        assertEquals(17, (result as SearchResult.Found).hits.size)
    }

    @Test
    fun `Google Books answers when OpenLibrary finds nothing or fails`() = runTest {
        val fromEmpty = DefaultBookSearch(primary = { emptyList() }, fallback = googleBooks)
        val fromFailure = DefaultBookSearch(primary = { throw IOException("offline") }, fallback = googleBooks)

        assertEquals(Fixtures.GOOGLE_STORY, (fromEmpty.searchByTitle("x") as SearchResult.Found).hits.single().isbn13)
        assertEquals(Fixtures.GOOGLE_STORY, (fromFailure.searchByTitle("x") as SearchResult.Found).hits.single().isbn13)
    }

    @Test
    fun `nothing anywhere is not found, a failure on the way is unavailable`() = runTest {
        val empty = TitleSearchSource { emptyList() }
        val offline = TitleSearchSource { throw IOException("offline") }

        assertEquals(SearchResult.NotFound, DefaultBookSearch(empty, empty).searchByTitle("x"))
        assertEquals(SearchResult.Unavailable, DefaultBookSearch(offline, empty).searchByTitle("x"))
        assertEquals(SearchResult.Unavailable, DefaultBookSearch(empty, offline).searchByTitle("x"))
    }
}
