package fr.noe.bda.data.book

/**
 * Every book is keyed by its ISBN-13, so whatever the user types or scans goes through
 * [toIsbn13] first.
 *
 * Check digits follow the ISBN standard: an ISBN-10's digits, weighted 10 down to 1, sum
 * to a multiple of 11 (`X` stands for 10, in the check position only); an ISBN-13's
 * digits, weighted alternately 1 and 3, sum to a multiple of 10, and it starts with the
 * book prefix 978 or 979.
 */
object Isbn {

    /**
     * The ISBN-13 for [input], or `null` when it isn't a valid ISBN-10 or ISBN-13.
     *
     * Hyphens and spaces are ignored and a lowercase `x` reads as `X`, so both
     * `0-306-40615-2` and `978 0 306 40615 7` give `9780306406157`. Any other character,
     * including an `ISBN` label, makes the input invalid. A barcode that is not a book
     * (an EAN-13 outside 978/979) is rejected too.
     */
    fun toIsbn13(input: String): String? {
        val compact = input.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        return when (compact.length) {
            10 -> compact.takeIf(::isValidIsbn10)?.let(::isbn10ToIsbn13)
            13 -> compact.takeIf(::isValidIsbn13)
            else -> null
        }
    }

    private fun isValidIsbn10(isbn10: String): Boolean {
        val body = isbn10.dropLast(1)
        val check = isbn10.last()
        if (!body.all(Char::isAsciiDigit) || !(check.isAsciiDigit() || check == 'X')) return false
        val sum = body.withIndex().sumOf { (i, c) -> (10 - i) * c.digitToInt() } +
            if (check == 'X') 10 else check.digitToInt()
        return sum % 11 == 0
    }

    private fun isValidIsbn13(isbn13: String): Boolean =
        isbn13.all(Char::isAsciiDigit) &&
            (isbn13.startsWith("978") || isbn13.startsWith("979")) &&
            isbn13.last().digitToInt() == isbn13CheckDigit(isbn13.dropLast(1))

    private fun isbn10ToIsbn13(isbn10: String): String {
        val first12 = "978" + isbn10.dropLast(1)
        return first12 + isbn13CheckDigit(first12)
    }

    private fun isbn13CheckDigit(first12: String): Int {
        val sum = first12.withIndex().sumOf { (i, c) -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }
        return (10 - sum % 10) % 10
    }
}

/** `Char.isDigit()` also accepts other scripts' digits, which an ISBN never contains. */
private fun Char.isAsciiDigit() = this in '0'..'9'
