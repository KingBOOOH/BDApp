package fr.noe.bda.data.bedetheque

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** bedetheque.com refused us twice in a row, fresh cookies included: stop asking until the next app start. */
class BedethequeBloqueException(message: String) : IOException(message)

/** A page as received, after redirects: a search with a single result lands on the album page itself. */
data class Page(val url: String, val html: String)

/** One HTTP exchange, for the feasibility report. `code` is -1 for a network failure. */
data class Requete(val url: String, val code: Int, val dureeMs: Long)

/**
 * The only way to reach bedetheque.com. Polite by construction: one request at a time, at
 * least [intervalleMs] apart, an explicit User-Agent, backoff on 429/503/timeouts.
 *
 * The site needs what a browser sends anyway: its `csrf_cookie_bel` cookie (set by the
 * first page loaded, the home page here) and a `Referer`, without which a search silently
 * returns nothing.
 */
class BedethequeClient(
    base: OkHttpClient,
    val baseUrl: String = Selectors.BASE_URL,
    private val intervalleMs: Long = 2_500,
    private val backoffMs: List<Long> = listOf(5_000, 15_000, 45_000),
    /** Monotonic: a wall clock set back (time sync, DST) would make the spacing wait for the jump. */
    private val maintenant: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val cookies = MemoireCookieJar()
    private val http = base.newBuilder()
        .cookieJar(cookies)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val verrou = Mutex()
    private var derniereRequete: Long? = null
    @Volatile private var bloque = false

    /** Called after every exchange; the feasibility screen listens to build its report. */
    @Volatile var observateur: ((Requete) -> Unit)? = null

    val estBloque: Boolean get() = bloque

    /** Throws [BedethequeBloqueException] on a repeated 403, [IOException] otherwise. */
    suspend fun get(url: String, referer: String = baseUrl): Page =
        recuperer(url, referer).let { Page(it.url, it.corps.decodeToString()) }

    /** A cover image: same throttle and session as the pages. */
    suspend fun octets(url: String): ByteArray = recuperer(url, baseUrl).corps

    private suspend fun recuperer(url: String, referer: String): Reponse = verrou.withLock {
        if (bloque) throw BedethequeBloqueException("bedetheque.com a refusé l'accès (403)")
        if (!cookies.aDesCookies()) charger(baseUrl, referer = null)

        var reponse = charger(url, referer)
        if (reponse.code == 403) {
            // The csrf cookie may have expired: start a fresh session once, as a browser would
            cookies.vider()
            charger(baseUrl, referer = null)
            reponse = charger(url, referer)
        }
        when {
            reponse.code == 403 -> {
                bloque = true
                throw BedethequeBloqueException("bedetheque.com a refusé l'accès (403)")
            }
            reponse.code !in 200..299 -> throw IOException("HTTP ${reponse.code} sur $url")
            else -> reponse
        }
    }

    private class Reponse(val code: Int, val url: String, val corps: ByteArray)

    /** One GET, spaced from the previous one, retried with backoff on 429/503/timeouts. */
    private suspend fun charger(url: String, referer: String?): Reponse {
        var tentative = 0
        while (true) {
            espacer()
            val debut = maintenant()
            val reponse = try {
                executer(url, referer)
            } catch (e: InterruptedIOException) {
                observateur?.invoke(Requete(url, -1, maintenant() - debut))
                if (tentative >= backoffMs.size) throw e
                null
            }
            reponse?.let { observateur?.invoke(Requete(url, it.code, maintenant() - debut)) }
            if (reponse != null && reponse.code != 429 && reponse.code != 503) return reponse
            if (tentative >= backoffMs.size) return reponse!!
            pause(backoffMs[tentative++])
        }
    }

    private suspend fun espacer() {
        val attente = (derniereRequete?.let { it + intervalleMs - maintenant() } ?: 0).coerceAtMost(intervalleMs)
        if (attente > 0) pause(attente)
        derniereRequete = maintenant()
    }

    private suspend fun executer(url: String, referer: String?): Reponse = withContext(Dispatchers.IO) {
        val requete = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        http.newCall(requete).execute().use { Reponse(it.code, it.request.url.toString(), it.body.bytes()) }
    }

    companion object {
        /** HTTP headers must be ASCII: OkHttp rejects accents. */
        const val USER_AGENT = "BDA-perso/1.0 (catalogue personnel de BD, usage prive, faible volume)"
    }
}

/** OkHttp keeps no cookies by default; the site only needs its csrf cookie for the session. */
private class MemoireCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }

    @Synchronized
    fun aDesCookies() = cookies.isNotEmpty()

    @Synchronized
    fun vider() = cookies.clear()
}
