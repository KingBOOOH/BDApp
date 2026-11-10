package fr.noe.bda.data.book

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** 0-306-40615-2 and 978-0-306-40615-7 are the worked examples on Wikipedia's ISBN page. */
class IsbnTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "0306406152,        9780306406157",
        "0-306-40615-2,     9780306406157",
        "080442957X,        9780804429573",
        "080442957x,        9780804429573",
        "0 8044 2957 X,     9780804429573",
    )
    fun `an ISBN-10 becomes its ISBN-13`(input: String, expected: String) {
        assertEquals(expected, Isbn.toIsbn13(input))
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "9780306406157,      9780306406157",
        "978-0-306-40615-7,  9780306406157",
        "'978 0 306 40615 7', 9780306406157",
        "9791032305690,      9791032305690",
    )
    fun `a valid ISBN-13 is kept, without separators`(input: String, expected: String) {
        assertEquals(expected, Isbn.toIsbn13(input))
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = [
            "0306406153",         // ISBN-10, wrong check digit
            "9780306406158",      // ISBN-13, wrong check digit
            "X306406152",         // X outside the check position
            "978030640615X",      // X is never valid in an ISBN-13
            "3017620422003",      // valid EAN-13 checksum, but not a book prefix
            "030640615",          // too short
            "97803064061570",     // too long
            "ISBN 9780306406157", // labels are not stripped
            "٠٣٠٦٤٠٦١٥٢",          // Arabic-Indic digits
            "",
        ],
    )
    fun `anything else is rejected`(input: String) {
        assertNull(Isbn.toIsbn13(input))
    }
}
