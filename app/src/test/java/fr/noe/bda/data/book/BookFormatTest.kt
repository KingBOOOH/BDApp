package fr.noe.bda.data.book

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Inputs are real `physical_format` values seen on OpenLibrary, plus common variants. */
class BookFormatTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        // Pocket, including "paperback" in the text, which must not win
        "Mass Market Paperback,     POCKET",
        "mass market paperback,     POCKET",
        "Mass-market paperback,     POCKET",
        "Pocket Book,               POCKET",
        "Poche,                     POCKET",
        "Livre de poche,            POCKET",
        "MMPB,                      POCKET",
        "Tascabile,                 POCKET",
        "Libro de bolsillo,         POCKET",
        // Hardcover
        "Hardcover,                 HARDCOVER",
        "hard cover,                HARDCOVER",
        "Hardback,                  HARDCOVER",
        "HC,                        HARDCOVER",
        "Relié,                     HARDCOVER",
        "relie,                     HARDCOVER",
        "Cartonné,                  HARDCOVER",
        "Gebunden,                  HARDCOVER",
        "Library Binding,           HARDCOVER",
        "Tapa dura,                 HARDCOVER",
        // Paperback
        "Paperback,                 PAPERBACK",
        "Trade Paperback,           PAPERBACK",
        "Softcover,                 PAPERBACK",
        "soft cover,                PAPERBACK",
        "pbk.,                      PAPERBACK",
        "Broché,                    PAPERBACK",
        "broche,                    PAPERBACK",
        "Taschenbuch,               PAPERBACK",
        "Kartoniert,                PAPERBACK",
        "Tapa blanda,               PAPERBACK",
        "Brossura,                  PAPERBACK",
    )
    fun `a known binding is recognised`(text: String, expected: BookFormat) {
        assertEquals(expected, BookFormat.parse(text))
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = ["E-book", "Audio CD", "", "Unknown Binding", "Chocolate"])
    fun `anything else is unknown`(text: String) {
        assertNull(BookFormat.parse(text))
    }
}
