package fr.noe.bda.data.lookup

import fr.noe.bda.data.book.BookFormat
import fr.noe.bda.data.lookup.Fixtures.HOBBIT
import fr.noe.bda.data.lookup.Fixtures.PETIT_PRINCE
import fr.noe.bda.data.lookup.openlibrary.OpenLibrarySource
import fr.noe.bda.data.lookup.openlibrary.parseDimensionsCm
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpenLibrarySourceTest {

    private val source = OpenLibrarySource(FixtureOpenLibraryApi())

    @Test
    fun `authors come from the books API when the edition has none`() = runTest {
        assertEquals(
            BookMetadata(
                isbn13 = HOBBIT,
                title = "The Hobbit",
                source = MetadataSource.OPEN_LIBRARY,
                workKey = "OL27482W",
                authors = listOf("J.R.R. Tolkien"),
                publisher = "Mariner Books",
                publishedDate = "2012",
                language = "eng",
                pageCount = 300,
                format = BookFormat.PAPERBACK,
                coverUrl = "https://covers.openlibrary.org/b/id/12003329-L.jpg",
            ),
            source.findByIsbn(HOBBIT),
        )
    }

    @Test
    fun `the binding comes from physical_format`() = runTest {
        assertEquals(BookFormat.HARDCOVER, source.findByIsbn(Fixtures.GOOGLE_STORY)!!.format)
        assertEquals(BookFormat.PAPERBACK, source.findByIsbn(PETIT_PRINCE)!!.format)
    }

    @Test
    fun `dimensions in inches become centimetres`() = runTest {
        val petitPrince = source.findByIsbn(PETIT_PRINCE)!!

        // "6.9 x 4.9 x 0.3 inches": height x width x depth
        assertEquals(17.526f, petitPrince.heightCm!!, 0.001f)
        assertEquals(0.762f, petitPrince.thicknessCm!!, 0.001f)
        assertEquals(listOf("Antoine de Saint-Exupéry"), petitPrince.authors)
        assertEquals("fre", petitPrince.language)
    }

    @Test
    fun `an unknown ISBN is not found`() = runTest {
        assertNull(source.findByIsbn("9791099999995"))
    }

    @Test
    fun `dimension units`() {
        assertEquals(24f, parseDimensionsCm("24 x 16 x 2 centimeters")!!.heightCm, 0.001f)
        assertEquals(2f, parseDimensionsCm("240 x 160 x 20 mm")!!.thicknessCm, 0.001f)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = ["9 x 6 inches", "9 x 6 x 1 furlongs", "x x  inches", ""])
    fun `unreadable dimensions are ignored`(text: String) {
        assertNull(parseDimensionsCm(text))
    }
}
