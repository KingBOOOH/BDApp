package fr.noe.bda.data.lookup

import fr.noe.bda.data.lookup.Fixtures.GOOGLE_STORY
import fr.noe.bda.data.lookup.Fixtures.HOBBIT
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksSource
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksVolume
import fr.noe.bda.data.lookup.googlebooks.toMetadata
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GoogleBooksSourceTest {

    private val api = FixtureGoogleBooksApi()

    @Test
    fun `the volume request supplies the dimensions the search leaves out`() = runTest {
        val book = GoogleBooksSource(api, apiKey = "k").findByIsbn(GOOGLE_STORY)

        assertEquals(
            BookMetadata(
                isbn13 = GOOGLE_STORY,
                title = "The Google story",
                source = MetadataSource.GOOGLE_BOOKS,
                authors = listOf("David A. Vise", "Mark Malseed"),
                publisher = "Random House Digital, Inc.",
                publishedDate = "2005-11-15",
                language = "en",
                pageCount = 207,
                heightCm = 24f,
                thicknessCm = 2.74f,
                coverUrl = "https://books.google.com/books?id=zyTCAlFPjgYC&printsec=frontcover&img=1&zoom=4&edge=curl&source=gbs_api",
            ),
            book,
        )
        assertEquals(listOf("search isbn:$GOOGLE_STORY key=k", "volume zyTCAlFPjgYC key=k"), api.calls)
    }

    @Test
    fun `a search hit for another ISBN is not used`() = runTest {
        assertNull(GoogleBooksSource(api, apiKey = "").findByIsbn(HOBBIT))
        // No key configured: the parameter is left out, and no volume is fetched.
        assertEquals(listOf("search isbn:$HOBBIT key=null"), api.calls)
    }

    @Test
    fun `a real volume with a height but no thickness, and covers over http`() {
        val volume = Fixtures.decode<GoogleBooksVolume>("googlebooks-volume-live-9780440335702.json")

        val book = volume.volumeInfo.toMetadata("9780440335702")!!

        assertEquals(24f, book.heightCm)
        assertNull(book.thicknessCm)
        assertEquals(384, book.pageCount)
        assertEquals("https://", book.coverUrl!!.take(8))
    }
}
