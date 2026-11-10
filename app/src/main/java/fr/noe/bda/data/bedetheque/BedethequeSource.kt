package fr.noe.bda.data.bedetheque

import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject

/**
 * bedetheque.com as the app sees it. There is no public API: this is page scraping, kept
 * behind an interface so tests and the bulk-update worker can use a fake.
 *
 * Every call may throw `IOException` (including [BedethequeBloqueException]).
 */
interface BedethequeSource {

    suspend fun rechercherParIsbn(isbn13: String): List<Candidat>

    suspend fun rechercherParTitre(titre: String, serie: String? = null): List<Candidat>

    /** Every album of every series whose name contains [serie] (the site ignores tome filters). */
    suspend fun rechercherParSerie(serie: String): List<Candidat>

    /** [url] may carry `#ed-<id>`; the whole album page is read either way. */
    suspend fun lireFiche(url: String): Fiche
}

class JsoupBedethequeSource @Inject constructor(
    private val client: BedethequeClient,
) : BedethequeSource {

    override suspend fun rechercherParIsbn(isbn13: String): List<Candidat> =
        rechercher(Selectors.PARAM_ISBN to isbn13)

    override suspend fun rechercherParTitre(titre: String, serie: String?): List<Candidat> =
        rechercher(Selectors.PARAM_TITRE to titre, Selectors.PARAM_SERIE to serie)

    /** The album page a single-result search landed on: the matcher asks for it right after. */
    @Volatile private var derniereFiche: Fiche? = null

    /** The last series search: a bulk run goes through a series tome by tome, so one search serves them all. */
    @Volatile private var derniereSerie: Pair<String, List<Candidat>>? = null

    override suspend fun rechercherParSerie(serie: String): List<Candidat> =
        derniereSerie?.takeIf { it.first == serie }?.second
            ?: rechercher(Selectors.PARAM_SERIE to serie).also { derniereSerie = serie to it }

    override suspend fun lireFiche(url: String): Fiche {
        derniereFiche?.takeIf { it.url == url.substringBefore('#') }?.let { return it }
        val page = client.get(url.substringBefore('#'))
        return parseFiche(page.html, page.url)
    }

    private suspend fun rechercher(vararg params: Pair<String, String?>): List<Candidat> {
        val url = (client.baseUrl + Selectors.RECHERCHE_ALBUMS).toHttpUrl().newBuilder()
            .apply { params.forEach { (nom, valeur) -> if (!valeur.isNullOrBlank()) addQueryParameter(nom, valeur) } }
            .build().toString()
        val page = client.get(url)
        parseRecherche(page.html).let { if (it.isNotEmpty()) return it }
        // A single match redirects (302) straight to the album page instead of listing it
        val fiche = parseFiche(page.html, page.url).takeIf { it.editions.isNotEmpty() } ?: return emptyList()
        derniereFiche = fiche
        return listOf(fiche.enCandidat())
    }
}
