package fr.noe.bda.data.bedetheque

sealed interface Correspondance {
    val raison: String

    data class Trouvee(val fiche: Fiche, val edition: Edition, override val raison: String) : Correspondance

    /** Several editions with different cotes: only the owner can tell which one is on the shelf. */
    data class AChoisir(val fiches: List<Fiche>, val editions: List<Edition>, override val raison: String) : Correspondance

    data class Aucune(override val raison: String) : Correspondance
}

/** At most this many album pages are opened per lookup: each costs one throttled request. */
private const val MAX_FICHES = 3

/**
 * Finds the bedetheque edition of a scanned album.
 *
 * By ISBN first: the result list gives the album pages, and each page's editions carry
 * their own ISBN. Reprints often keep the EO's ISBN, so one ISBN can match several editions;
 * that only matters when their cotes differ. Without an ISBN hit, falls back to the series
 * and tome number (albums imported from a list), then to the title. Neither is trusted enough
 * to pick between differently-rated editions.
 */
suspend fun BedethequeSource.trouverEdition(
    isbn13: String?,
    titre: String?,
    serie: String? = null,
    tome: String? = null,
): Correspondance {
    if (isbn13 != null) {
        val candidats = rechercherParIsbn(isbn13)
        if (candidats.isNotEmpty()) {
            val fiches = lireFiches(candidats)
            val editions = fiches.flatMap { it.editions }
            val memeIsbn = editions.filter { it.isbn13 == isbn13 }
            // The search matched on ISBN even if a sheet shows it differently: trust the result list then
            val ids = candidats.map(::idEdition).toSet()
            val retenues = memeIsbn.ifEmpty { editions.filter { it.id in ids } }
            return choisir(fiches, retenues, "ISBN")
        }
    }
    if (serie != null && tome != null) return parSerieEtTome(serie, tome)
    val recherche = titre?.let(::titreAlbum)?.takeIf { it.isNotBlank() }
        ?: return Correspondance.Aucune("aucun résultat pour l'ISBN, pas de titre")
    val candidats = rechercherParTitre(recherche, serie)
    if (candidats.isEmpty()) return Correspondance.Aucune("aucun résultat pour l'ISBN ni pour « $recherche »")
    val exacts = candidats.filter { normaliser(it.titre) == normaliser(recherche) }
    val fiches = lireFiches(exacts.ifEmpty { candidats })
    val editions = fiches.flatMap { it.editions }
    return if (exacts.isEmpty() || fiches.size > 1) {
        Correspondance.AChoisir(fiches, editions, "titre « $recherche » : ${candidats.size} résultat(s), aucun sûr")
    } else {
        choisir(fiches, editions, "titre")
    }
}

/**
 * The site ignores tome filters, so the whole series comes back (every series containing the
 * name, too: "Lucky Luke" gives 1 077 albums) and the tome is picked here.
 */
private suspend fun BedethequeSource.parSerieEtTome(serie: String, tome: String): Correspondance {
    val texte = serie.replace(Regex("""\(.*?\)"""), " ").trim()
    val candidats = rechercherParSerie(texte).ifEmpty {
        // "ATALANTE - la légende" may be listed under another wording: try its first word
        texte.split(Regex("""[^\p{L}\p{N}]+""")).firstOrNull { it.length >= 4 }?.let { rechercherParSerie(it) }.orEmpty()
    }
    val cle = cleSerie(serie)
    val memeSerie = candidats.filter { cleSerie(it.serie) == cle }
    val auTome = memeSerie.ifEmpty { candidats }.filter { memeTome(it.numero, tome) }
    if (auTome.isEmpty()) return Correspondance.Aucune("série « $serie », tome $tome : introuvable")
    val fiches = lireFiches(auTome)
    val editions = fiches.flatMap { it.editions }
    // The same number in several series (Alix, Alix Senator…): only the owner can tell
    if (memeSerie.isEmpty() && auTome.map { cleSerie(it.serie) }.distinct().size > 1) {
        return Correspondance.AChoisir(fiches, editions, "série « $serie » : plusieurs séries ont un tome $tome")
    }
    return choisir(fiches, editions, "série + tome")
}

/** A series name to compare: no case, accents, punctuation or articles. "Aigles de Rome (Les)" = "aigles de rome". */
fun cleSerie(serie: String?): String =
    normaliser(serie.orEmpty()).split(Regex("""[^\p{L}\p{N}]+"""))
        .filter { it.isNotEmpty() && it !in ARTICLES }
        .joinToString(" ")

private val ARTICLES = setOf("le", "la", "les", "l", "un", "une", "the")

/** "2" = "2", "HS2" = "hs 2"; a bare "HS" or "INT" accepts any numbered one. */
internal fun memeTome(numero: String?, tome: String): Boolean {
    val n = numero.orEmpty().uppercase().filterNot(Char::isWhitespace)
    val t = tome.uppercase().filterNot(Char::isWhitespace)
    return n == t || (t in setOf("HS", "INT") && n.startsWith(t))
}

private suspend fun BedethequeSource.lireFiches(candidats: List<Candidat>): List<Fiche> =
    candidats.distinctBy(::idAlbum).take(MAX_FICHES).map { lireFiche(it.url) }

internal fun choisir(fiches: List<Fiche>, editions: List<Edition>, via: String): Correspondance {
    fun ficheDe(e: Edition) = fiches.first { e in it.editions }
    return when {
        editions.isEmpty() -> Correspondance.Aucune("$via : aucune édition lisible sur la fiche")
        editions.size == 1 -> Correspondance.Trouvee(ficheDe(editions[0]), editions[0], "$via : édition unique")
        editions.map { cleDeCote(it.cote) }.distinct().size == 1 ->
            Correspondance.Trouvee(ficheDe(editions[0]), editions[0], "$via : ${editions.size} éditions, même cote")
        else -> Correspondance.AChoisir(fiches, editions, "$via : ${editions.size} éditions, cotes différentes")
    }
}

private fun cleDeCote(cote: Cote): Any = if (cote is Cote.Estimee) cote.minEuros to cote.maxEuros else cote

private fun idAlbum(c: Candidat) = Selectors.ID_ALBUM.find(c.url)?.groupValues?.get(1) ?: c.url

/** A result without `#ed-` is the album's main edition, whose id is the album's. */
private fun idEdition(c: Candidat): Long? =
    (Selectors.ID_EDITION.find(c.url) ?: Selectors.ID_ALBUM.find(c.url))?.groupValues?.get(1)?.toLongOrNull()

/**
 * The album's own title out of what OpenLibrary or Google Books call it, e.g.
 * `Blacksad, Tome 1 : Quelque part entre les ombres` → `Quelque part entre les ombres`.
 */
internal fun titreAlbum(titre: String): String =
    titre.split(Regex("""\s+[-–]\s+|\s*:\s+""")).last()
        .replace(Regex("""(?i)\b(tome|t\.)\s*\d+\b"""), "")
        .trim(' ', ',', '.', '-')
