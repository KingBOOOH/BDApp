package fr.noe.bda.data.bedetheque

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real pages saved on 25 Oct 2026 under `src/test/resources/bedetheque/`. */
class BedethequeHtmlTest {

    @Test
    fun `an ISBN search lists every edition sharing it`() {
        val candidats = parseRecherche(page("recherche-isbn-blacksad.html"))

        assertEquals(11, candidats.size)
        assertEquals("Blacksad", candidats[0].serie)
        assertEquals("Quelque part entre les ombres", candidats[0].titre)
        assertTrue(candidats.all { it.url.startsWith("https://www.bedetheque.com/BD-Blacksad-Tome-1") })
    }

    @Test
    fun `a page without results gives no candidate`() {
        assertEquals(emptyList<Candidat>(), parseRecherche(page("recherche-vide.html")))
    }

    @Test
    fun `an album page gives the series, the tome and each edition's cote and ISBN`() {
        val fiche = parseFiche(page("album-blacksad.html"), BLACKSAD)

        assertEquals("Blacksad", fiche.serie)
        assertEquals(listOf("Díaz Canalès, Juan", "Guarnido, Juanjo"), fiche.auteurs)
        assertEquals("1", fiche.tome)
        assertEquals("Quelque part entre les ombres", fiche.titre)
        assertEquals(15, fiche.editions.size)
        val eo = fiche.editions.first { it.id == 15161L }
        assertEquals(Cote.Estimee(100, 150, "de 100 à 150 euros"), eo.cote)
        assertEquals("9782205049657", eo.isbn13)
        assertEquals("11/2000", eo.depotLegal)
        assertEquals("$BLACKSAD#ed-15161", eo.url)
        assertEquals(11, fiche.editions.count { it.isbn13 == "9782205049657" })
    }

    @Test
    fun `every edition on the saved pages has a readable cote`() {
        val editions = parseFiche(page("album-blacksad.html"), BLACKSAD).editions +
            parseFiche(page("album-asterix.html"), ASTERIX).editions

        assertEquals(emptyList<Edition>(), editions.filter { it.cote is Cote.Illisible || it.cote is Cote.Absente })
    }

    @Test
    fun `cote texts`() {
        assertEquals(Cote.Estimee(1500, 2000, "de 1 500 à 2 000 euros"), parseCote("de 1 500 à 2 000 euros"))
        assertEquals(Cote.Estimee(null, 10, "moins de 10 euros"), parseCote("moins de 10 euros"))
        assertEquals(Cote.Estimee(500, null, "plus de 500 euros"), parseCote("plus de 500 euros"))
        assertEquals(Cote.Estimee(30, 30, "30 euros"), parseCote("30 euros"))
        assertEquals(Cote.NonCotee, parseCote("non coté"))
        assertEquals(Cote.Illisible("sur demande"), parseCote("sur demande"))
    }

    @Test
    fun `album title out of a lookup title`() {
        assertEquals("Quelque part entre les ombres", titreAlbum("Blacksad, Tome 1 : Quelque part entre les ombres"))
        assertEquals("Le Lotus bleu", titreAlbum("Tintin - Le Lotus bleu"))
        assertEquals("Spider-Man", titreAlbum("Spider-Man"))
    }

    companion object {
        const val BLACKSAD = "https://www.bedetheque.com/BD-Blacksad-Tome-1-Quelque-part-entre-les-ombres-15161.html"
        const val ASTERIX = "https://www.bedetheque.com/BD-Asterix-Hachette-Tome-1-Asterix-le-Gaulois-57582.html"

        fun page(nom: String): String =
            checkNotNull(BedethequeHtmlTest::class.java.getResource("/bedetheque/$nom")) { "missing fixture $nom" }.readText()
    }
}
