package fr.noe.bda.data.book

import java.text.Normalizer

/** An edition's binding, which gives a likely height when the real one is unknown. */
enum class BookFormat(val typicalHeightCm: Float, private val keywords: Regex) {

    /** Mass-market paperback, "livre de poche": about 18 × 11 cm. */
    POCKET(18f, Regex("""\b(mass[ -]?market|pocket|poche|mmpb|tascabile|bolsillo)\b""")),

    /** Hardback: about 24 cm. */
    HARDCOVER(24f, Regex("""\b(hard ?cover|hard ?back|hc|relie|cartonne|gebunden|library binding|tapa dura|rilegato)\b""")),

    /** Trade paperback, "broché": about 21 cm. German "Taschenbuch" names any paperback, not only pocket ones. */
    PAPERBACK(21f, Regex("""\b(paper ?back|soft ?cover|soft ?back|pbk|broche|taschenbuch|kartoniert|tapa blanda|brossura)\b"""));

    companion object {

        /**
         * The format named in OpenLibrary's `physical_format`, free text typed by contributors
         * in any case and language ("Mass Market Paperback", "mass market paperback", "Broché",
         * "Relié"…), or `null` when none matches (e-book, audio CD, nothing recognisable).
         *
         * Pocket is checked first: "mass market paperback" also contains "paperback".
         */
        fun parse(text: String): BookFormat? {
            // "Relié" and "Broché" are often typed without accents, so compare without them.
            val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(ACCENTS, "")
            return entries.firstOrNull { it.keywords.containsMatchIn(plain) }
        }

        private val ACCENTS = Regex("""\p{Mn}""")
    }
}
