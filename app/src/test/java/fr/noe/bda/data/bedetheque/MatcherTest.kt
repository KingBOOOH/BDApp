package fr.noe.bda.data.bedetheque

import fr.noe.bda.data.bedetheque.BedethequeHtmlTest.Companion.page
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatcherTest {

    /** Serves the saved pages; anything else is an empty result. */
    private val source = object : BedethequeSource {
        override suspend fun rechercherParIsbn(isbn13: String) = when (isbn13) {
            ASTERIX_ISBN -> parseRecherche(page("recherche-isbn-asterix.html"))
            BLACKSAD_ISBN -> parseRecherche(page("recherche-isbn-blacksad.html"))
            else -> emptyList()
        }

        override suspend fun rechercherParTitre(titre: String, serie: String?) = emptyList<Candidat>()
        override suspend fun rechercherParSerie(serie: String) = serieFactice(serie)

        override suspend fun lireFiche(url: String) = when {
            "-9003.html" in url -> aiglesTome3
            "-57582.html" in url -> parseFiche(page("album-asterix.html"), url)
            "-15161.html" in url -> parseFiche(page("album-blacksad.html"), url)
            else -> error("unexpected page $url")
        }
    }

    @Test
    fun `reprints sharing an ISBN and a cote are matched without asking`() = runTest {
        val resultat = source.trouverEdition(ASTERIX_ISBN, titre = null)

        assertInstanceOf(Correspondance.Trouvee::class.java, resultat)
        resultat as Correspondance.Trouvee
        assertEquals("ISBN : 14 éditions, même cote", resultat.raison)
        assertEquals(Cote.NonCotee, resultat.edition.cote)
        assertEquals("Astérix (Hachette)", resultat.fiche.serie)
    }

    @Test
    fun `an EO sharing its ISBN with unrated reprints is left to the owner`() = runTest {
        val resultat = source.trouverEdition(BLACKSAD_ISBN, titre = null)

        assertInstanceOf(Correspondance.AChoisir::class.java, resultat)
        resultat as Correspondance.AChoisir
        assertEquals(11, resultat.editions.size)
        assertEquals(1, resultat.editions.count { it.cote == Cote.Estimee(100, 150, "de 100 à 150 euros") })
    }

    @Test
    fun `series and tome find the album in its own series, not in a namesake`() = runTest {
        val resultat = source.trouverEdition(null, titre = null, serie = "aigles de rome (les)", tome = "3")

        assertInstanceOf(Correspondance.Trouvee::class.java, resultat)
        resultat as Correspondance.Trouvee
        assertEquals("série + tome : 2 éditions, même cote", resultat.raison)
        assertEquals("3", resultat.fiche.tome)
    }

    @Test
    fun `a tome missing from the series finds nothing`() = runTest {
        assertInstanceOf(Correspondance.Aucune::class.java, source.trouverEdition(null, null, "Aigles de Rome", "9"))
    }

    @Test
    fun `series names and tomes compare loosely`() {
        assertEquals(cleSerie("Aigles de Rome (Les)"), cleSerie("aigles de rome (les)"))
        assertEquals(cleSerie("Astérix"), cleSerie("ASTERIX"))
        assertTrue(memeTome("HS2", "hs 2"))
        assertTrue(memeTome("INT1", "INT"))
        assertFalse(memeTome("12", "1"))
    }

    @Test
    fun `an unknown ISBN without a title finds nothing`() = runTest {
        assertInstanceOf(Correspondance.Aucune::class.java, source.trouverEdition("9780000000002", titre = null))
    }

    /** A series search as the site answers it: the whole series, plus a namesake series. */
    private fun serieFactice(serie: String) = if ("aigles" in serie.lowercase()) {
        listOf(
            Candidat("$BASE/BD-Aigles-de-Rome-Tome-2-9002.html", "Aigles de Rome (Les)", "2", "Livre II", null, null),
            Candidat("$BASE/BD-Aigles-de-Rome-Tome-3-9003.html", "Aigles de Rome (Les)", "3", "Livre III", null, null),
            Candidat("$BASE/BD-Aigles-de-Rome-Sketchbook-9103.html", "Aigles de Rome - Sketchbook", "3", "Sketch", null, null),
        )
    } else {
        emptyList()
    }

    private val aiglesTome3 = Fiche(
        "$BASE/BD-Aigles-de-Rome-Tome-3-9003.html", "Aigles de Rome (Les)", "3", "Livre III", listOf("Marini, Enrico"),
        listOf(9003L, 9004L).map { id ->
            Edition(id, "$BASE/BD-Aigles-de-Rome-Tome-3-9003.html#ed-$id", "Tome 3", "Livre III", null, "Dargaud", null,
                Cote.Estimee(10, 15, "de 10 à 15 euros"), null, null)
        },
    )

    companion object {
        const val BASE = "https://www.bedetheque.com"
        const val ASTERIX_ISBN = "9782012101333"
        const val BLACKSAD_ISBN = "9782205049657"
    }
}
