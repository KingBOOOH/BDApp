package fr.noe.bda.ui.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScanOutcomeTest {

    @Test
    fun `a frame without a barcode reports nothing`() {
        assertNull(scanOutcome(emptyList()))
    }

    @Test
    fun `a book's EAN-13 is its ISBN`() {
        assertEquals(ScanOutcome.Isbn13("9780547928227"), scanOutcome(listOf("9780547928227")))
    }

    @Test
    fun `a grocery EAN-13 is not a book`() {
        // Valid EAN-13 check digit, but outside the 978/979 book prefixes.
        assertEquals(ScanOutcome.NotABook, scanOutcome(listOf("4006381333931")))
    }

    @Test
    fun `a misread check digit is not taken for an ISBN`() {
        assertEquals(ScanOutcome.NotABook, scanOutcome(listOf("9780547928228")))
    }

    @Test
    fun `the ISBN wins when a price label is in the same frame`() {
        assertEquals(
            ScanOutcome.Isbn13("9782070612758"),
            scanOutcome(listOf("4006381333931", "9782070612758")),
        )
    }
}
