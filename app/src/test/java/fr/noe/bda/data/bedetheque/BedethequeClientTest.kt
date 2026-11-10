package fr.noe.bda.data.bedetheque

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BedethequeClientTest {

    private val serveur = MockWebServer()
    private var horloge = 0L
    private val pauses = mutableListOf<Long>()
    private lateinit var client: BedethequeClient

    @BeforeEach
    fun demarrer() {
        serveur.start()
        client = BedethequeClient(
            OkHttpClient(),
            baseUrl = serveur.url("/").toString(),
            maintenant = { horloge },
            pause = { pauses += it; horloge += it },
        )
    }

    @AfterEach
    fun arreter() = serveur.close()

    private fun reponse(code: Int, corps: String = "", cookie: String? = null) = MockResponse.Builder()
        .code(code).body(corps)
        .apply { if (cookie != null) addHeader("Set-Cookie", "csrf_cookie_bel=$cookie; Path=/") }
        .build()

    @Test
    fun `the home page is loaded first, then requests carry its cookie, a Referer and the User-Agent`() = runTest {
        serveur.enqueue(reponse(200, cookie = "abc"))
        serveur.enqueue(reponse(200, "page"))

        assertEquals("page", client.get(serveur.url("/page").toString()).html)

        serveur.takeRequest()
        val requete = serveur.takeRequest()
        assertEquals("csrf_cookie_bel=abc", requete.headers["Cookie"])
        assertEquals(serveur.url("/").toString(), requete.headers["Referer"])
        assertEquals(BedethequeClient.USER_AGENT, requete.headers["User-Agent"])
        assertEquals(listOf(2_500L), pauses) // spaced from the home page request
    }

    @Test
    fun `a 403 gets one retry with a fresh session`() = runTest {
        serveur.enqueue(reponse(200, cookie = "vieux"))
        serveur.enqueue(reponse(403))
        serveur.enqueue(reponse(200, cookie = "neuf"))
        serveur.enqueue(reponse(200, "page"))

        assertEquals("page", client.get(serveur.url("/page").toString()).html)
        assertEquals(4, serveur.requestCount)
    }

    @Test
    fun `a second 403 blocks the client until restart`() = runTest {
        serveur.enqueue(reponse(200, cookie = "a"))
        serveur.enqueue(reponse(403))
        serveur.enqueue(reponse(200, cookie = "b"))
        serveur.enqueue(reponse(403))

        assertThrows<BedethequeBloqueException> { client.get(serveur.url("/page").toString()) }
        assertThrows<BedethequeBloqueException> { client.get(serveur.url("/autre").toString()) }
        assertEquals(4, serveur.requestCount)
    }

    @Test
    fun `a clock set back never makes the spacing wait longer than the interval`() = runTest {
        serveur.enqueue(reponse(200, cookie = "a"))
        serveur.enqueue(reponse(200, "page"))
        serveur.enqueue(reponse(200, "autre"))
        client.get(serveur.url("/page").toString())

        horloge -= 3_600_000 // an hour back

        client.get(serveur.url("/autre").toString())
        assertEquals(listOf(2_500L, 2_500L), pauses)
    }

    @Test
    fun `a 429 is retried after a backoff`() = runTest {
        serveur.enqueue(reponse(200, cookie = "a"))
        serveur.enqueue(reponse(429))
        serveur.enqueue(reponse(200, "page"))

        assertEquals("page", client.get(serveur.url("/page").toString()).html)
        assertEquals(listOf(2_500L, 5_000L), pauses) // the backoff already exceeds the spacing
    }
}
