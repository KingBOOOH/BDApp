package fr.noe.bda.data.lookup

import fr.noe.bda.data.lookup.Fixtures.GOOGLE_STORY
import fr.noe.bda.data.lookup.Fixtures.HOBBIT
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksSource
import fr.noe.bda.data.lookup.openlibrary.OpenLibrarySource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException

class DefaultBookLookupTest {

    private val openLibrary = OpenLibrarySource(FixtureOpenLibraryApi())
    private val googleBooks = GoogleBooksSource(FixtureGoogleBooksApi(), apiKey = "")

    @Test
    fun `where both sources have a field, OpenLibrary's wins`() = runTest {
        val fromOpenLibrary = openLibrary.findByIsbn(GOOGLE_STORY)!!
        val fromGoogleBooks = googleBooks.findByIsbn(GOOGLE_STORY)!!

        val merged = fromOpenLibrary.filledFrom(fromGoogleBooks)

        // The sources disagree on this edition: 336 pages and Delacorte Press against 207 and Random House.
        assertEquals(fromOpenLibrary, merged)
        assertEquals(336, merged.pageCount)
        assertEquals("Delacorte Press", merged.publisher)
    }

    @Test
    fun `a complete OpenLibrary record never asks Google Books`() = runTest {
        val lookup = DefaultBookLookup(openLibrary, fallback = { fail("Google Books was asked") })

        val result = lookup.findByIsbn(GOOGLE_STORY)

        assertEquals(MetadataSource.OPEN_LIBRARY, (result as LookupResult.Found).metadata.source)
    }

    @Test
    fun `fields OpenLibrary lacks are filled from Google Books`() = runTest {
        // OpenLibrary has no dimensions for this edition. Hand-written, as no Google Books response is saved for it.
        val googleHobbit = BookMetadata(
            isbn13 = HOBBIT,
            title = "The Hobbit",
            source = MetadataSource.GOOGLE_BOOKS,
            authors = listOf("J. R. R. Tolkien"),
            pageCount = 320,
            heightCm = 20.3f,
            thicknessCm = 2.1f,
        )
        val lookup = DefaultBookLookup(openLibrary, fallback = { googleHobbit })

        val hobbit = (lookup.findByIsbn(HOBBIT) as LookupResult.Found).metadata

        assertEquals(openLibrary.findByIsbn(HOBBIT)!!.copy(heightCm = 20.3f, thicknessCm = 2.1f), hobbit)
        assertEquals(MetadataSource.OPEN_LIBRARY, hobbit.source)
        assertEquals(listOf("J.R.R. Tolkien"), hobbit.authors)
    }

    @Test
    fun `Google Books answers alone when OpenLibrary doesn't know the ISBN`() = runTest {
        val lookup = DefaultBookLookup(primary = { null }, fallback = googleBooks)

        val result = lookup.findByIsbn(GOOGLE_STORY)

        assertEquals(googleBooks.findByIsbn(GOOGLE_STORY), (result as LookupResult.Found).metadata)
    }

    @Test
    fun `Google Books answers alone when OpenLibrary is unreachable`() = runTest {
        val lookup = DefaultBookLookup(primary = { throw IOException("offline") }, fallback = googleBooks)

        val result = lookup.findByIsbn(GOOGLE_STORY)

        assertEquals(MetadataSource.GOOGLE_BOOKS, (result as LookupResult.Found).metadata.source)
    }

    @Test
    fun `nothing found anywhere is NotFound`() = runTest {
        assertEquals(LookupResult.NotFound, DefaultBookLookup({ null }, { null }).findByIsbn(HOBBIT))
    }

    @Test
    fun `nothing found while a source was unreachable is Unavailable`() = runTest {
        val lookup = DefaultBookLookup(primary = { null }, fallback = { throw IOException("offline") })

        assertEquals(LookupResult.Unavailable, lookup.findByIsbn(HOBBIT))
    }
}
