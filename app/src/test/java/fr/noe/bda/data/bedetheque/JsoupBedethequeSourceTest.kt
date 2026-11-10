package fr.noe.bda.data.bedetheque

import fr.noe.bda.data.bedetheque.BedethequeHtmlTest.Companion.page
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class JsoupBedethequeSourceTest {

    private val serveur = MockWebServer().apply { start() }
    private val source = JsoupBedethequeSource(
        BedethequeClient(OkHttpClient(), baseUrl = serveur.url("/").toString(), pause = {}),
    )

    @AfterEach
    fun arreter() = serveur.close()

    @Test
    fun `a single-result search lands on the album page, which is not fetched again`() = runTest {
        serveur.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "csrf_cookie_bel=a; Path=/").build())
        // What bedetheque answered on 25 Oct 2026 for RechISBN=9782800147215
        serveur.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/BD-Zombillenium-Tome-1-Gretchen-113245.html").build())
        serveur.enqueue(MockResponse.Builder().code(200).body(page("album-zombillenium.html")).build())

        val resultat = source.trouverEdition("9782800147215", titre = null)

        assertInstanceOf(Correspondance.Trouvee::class.java, resultat)
        resultat as Correspondance.Trouvee
        assertEquals("ISBN : édition unique", resultat.raison)
        assertEquals("Zombillénium", resultat.fiche.serie)
        assertEquals("1", resultat.fiche.tome)
        assertEquals(3, serveur.requestCount)
    }
}
