package fr.noe.bda.data.bedetheque

import fr.noe.bda.data.book.Isbn
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer

/** One line of a search result: an edition, before its album page is read. */
data class Candidat(
    /** Album page URL, with `#ed-<id>` when the result is a reprint rather than the album's main edition. */
    val url: String,
    val serie: String,
    /** The album's number in its series: `1`, `HS2`, `INT`… (`#` and the edition suffix removed). */
    val numero: String?,
    val titre: String,
    val editeur: String?,
    val couvertureUrl: String?,
)

/** An album page: the album and every edition the site lists for it. */
data class Fiche(
    val url: String,
    val serie: String?,
    /** `1`, `HS2`, `INT`… without the edition suffix. */
    val tome: String?,
    val titre: String?,
    val auteurs: List<String>,
    val editions: List<Edition>,
)

data class Edition(
    val id: Long,
    /** Album page URL + `#ed-<id>`: what the app stores to refresh this edition's cote later. */
    val url: String,
    /** As the edition header shows it, e.g. `Tome 1 b2004`. */
    val libelle: String,
    val titre: String?,
    val depotLegal: String?,
    val editeur: String?,
    /** ISBN-13, or `null` when the edition has none or it is invalid. */
    val isbn13: String?,
    val cote: Cote,
    val couvertureUrl: String?,
    /** The site's "Info édition" note, which often tells an EO from a reprint. */
    val note: String?,
)

sealed interface Cote {
    /** In whole euros, as the site gives them. A "moins de 10" has no minimum, a "plus de 500" no maximum. */
    data class Estimee(val minEuros: Int?, val maxEuros: Int?, val texte: String) : Cote

    data object NonCotee : Cote

    /** The sheet has no Estimation line. */
    data object Absente : Cote

    /** An Estimation text none of the patterns in [Selectors] reads: the markup or wording changed. */
    data class Illisible(val texte: String) : Cote
}

internal fun parseRecherche(html: String, baseUrl: String = Selectors.BASE_URL): List<Candidat> =
    Jsoup.parse(html, baseUrl).select(Selectors.RESULTATS).mapNotNull { li ->
        val lien = li.selectFirst(Selectors.RESULTAT_LIEN) ?: return@mapNotNull null
        Candidat(
            url = lien.absUrl("href"),
            serie = li.texte(Selectors.RESULTAT_SERIE).orEmpty(),
            numero = li.selectFirst(Selectors.RESULTAT_NUMERO)?.ownText()?.removePrefix("#")?.trim()?.ifBlank { null },
            titre = li.texte(Selectors.RESULTAT_TITRE).orEmpty(),
            editeur = li.texte(Selectors.RESULTAT_EDITEUR),
            couvertureUrl = li.selectFirst(Selectors.RESULTAT_COUVERTURE)?.absUrl("src")?.ifBlank { null },
        )
    }

internal fun parseFiche(html: String, url: String): Fiche {
    val doc = Jsoup.parse(html, url)
    val pageUrl = url.substringBefore('#')
    // "1. Astérix le Gaulois" (the edition suffix sits in a span.bdt-numa), or just the title for a one-shot
    val sousTitre = doc.selectFirst(Selectors.SOUS_TITRE)?.ownText()?.trim().orEmpty()
    val numero = Selectors.TOME_ET_TITRE.matchEntire(sousTitre)
    val editions = doc.select(Selectors.EDITIONS).mapNotNull { parseEdition(it, pageUrl) }
    return Fiche(
        url = pageUrl,
        serie = doc.texte(Selectors.SERIE),
        tome = numero?.groupValues?.get(1),
        // A one-shot's header has no subtitle: its editions still name it
        titre = (numero?.groupValues?.get(2) ?: sousTitre).ifBlank { null } ?: editions.firstNotNullOfOrNull { it.titre },
        auteurs = doc.select(Selectors.AUTEURS)
            .filter { span -> Selectors.ROLES_AUTEUR.any { it in normaliser(span.selectFirst("small")?.text().orEmpty()) } }
            .mapNotNull { it.selectFirst("a")?.text() }
            .distinct(),
        editions = editions,
    )
}

private fun parseEdition(article: Element, pageUrl: String): Edition? {
    val id = article.id().removePrefix("ed-").toLongOrNull() ?: return null
    val fiche = article.select(Selectors.EDITION_FICHE).associate { dt ->
        normaliser(dt.text()) to dt.nextElementSibling()?.takeIf { it.tagName() == "dd" }?.text()
    }
    return Edition(
        id = id,
        url = "$pageUrl#ed-$id",
        libelle = article.texte(Selectors.EDITION_TOME).orEmpty(),
        titre = fiche[Selectors.DT_TITRE],
        depotLegal = fiche[Selectors.DT_DEPOT_LEGAL],
        editeur = fiche[Selectors.DT_EDITEUR],
        isbn13 = fiche[Selectors.DT_ISBN]?.let(Isbn::toIsbn13),
        cote = if (Selectors.DT_ESTIMATION in fiche) parseCote(fiche[Selectors.DT_ESTIMATION].orEmpty()) else Cote.Absente,
        couvertureUrl = article.selectFirst(Selectors.EDITION_COUVERTURE)?.absUrl("href")?.ifBlank { null },
        note = article.texte(Selectors.EDITION_NOTE)?.removePrefix("Info édition")?.trim(),
    )
}

internal fun Fiche.enCandidat() = Candidat(
    url = url,
    serie = serie.orEmpty(),
    numero = tome,
    titre = titre.orEmpty(),
    editeur = editions.firstOrNull()?.editeur,
    couvertureUrl = editions.firstOrNull()?.couvertureUrl,
)

internal fun parseCote(texte: String): Cote {
    val t = texte.replace(' ', ' ').trim()
    fun euros(s: String) = s.filter(Char::isDigit).toIntOrNull()
    Selectors.NON_COTE.find(t)?.let { return Cote.NonCotee }
    Selectors.COTE_FOURCHETTE.find(t)?.let { return Cote.Estimee(euros(it.groupValues[1]), euros(it.groupValues[2]), t) }
    Selectors.COTE_MOINS_DE.find(t)?.let { return Cote.Estimee(null, euros(it.groupValues[1]), t) }
    Selectors.COTE_PLUS_DE.find(t)?.let { return Cote.Estimee(euros(it.groupValues[1]), null, t) }
    Selectors.COTE_SIMPLE.find(t)?.let { m -> return euros(m.groupValues[1]).let { Cote.Estimee(it, it, t) } }
    return Cote.Illisible(t)
}

/** Lowercase, accents and trailing colon removed: `Dépôt légal :` → `depot legal`. */
internal fun normaliser(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .lowercase().replace(' ', ' ').trim().removeSuffix(":").trim()

private fun Element.texte(css: String): String? = selectFirst(css)?.text()?.trim()?.ifBlank { null }
