package fr.noe.bda.data.bedetheque

/**
 * Everything that depends on bedetheque.com's markup, in one place: when the site changes,
 * this file (and the saved pages under `src/test/resources/bedetheque/`) is what to update.
 *
 * Observed on 25 Oct 2026 (redesign with `bdt-*` classes):
 * - search: `search/albums?RechISBN=…` (the site's own quick search sends an EAN there).
 *   It answers 200 with **no results** unless the request carries a `Referer` and the
 *   `csrf_cookie_bel` cookie set by any earlier page.
 * - an album page holds every edition (printing) of the album, each in its own
 *   `article.bdt-edition#ed-<id>` with a `dt`/`dd` sheet. Reprints often share the EO's ISBN.
 */
internal object Selectors {

    const val BASE_URL = "https://www.bedetheque.com/"
    const val RECHERCHE_ALBUMS = "search/albums"
    const val PARAM_ISBN = "RechISBN"
    const val PARAM_SERIE = "RechSerie"
    const val PARAM_TITRE = "RechTitre"

    // Search results
    const val RESULTATS = "section.bdt-resultats ul.bdt-liste > li"
    const val RESULTAT_LIEN = "a[href]"
    const val RESULTAT_SERIE = ".bdt-liste-l1 b"
    const val RESULTAT_NUMERO = ".bdt-liste-num"
    const val RESULTAT_TITRE = ".bdt-liste-titre"
    const val RESULTAT_EDITEUR = ".bdt-liste-editeur"
    const val RESULTAT_COUVERTURE = "img"

    // Album page
    const val SERIE = ".bdt-ah-top h1"
    const val SOUS_TITRE = "h2.bdt-ah-sub"
    /** One `span` per contributor: `<a>Nom, Prénom</a> <small>(Scénario)</small>`. */
    const val AUTEURS = ".bdt-ah-auteurs > span"
    /** Kept roles (after [normaliser]); colourists, prefaces, letterers are left out. */
    val ROLES_AUTEUR = listOf("scenario", "dessin")
    const val EDITIONS = "article.bdt-edition"
    const val EDITION_TOME = ".bdt-edition-head h3"
    const val EDITION_FICHE = "dl.bdt-sheet > dt"
    const val EDITION_COUVERTURE = "a.browse-couvertures"
    const val EDITION_NOTE = ".bdt-note"

    // `dt` labels, compared after [normaliser]
    const val DT_TITRE = "titre"
    const val DT_DEPOT_LEGAL = "depot legal"
    const val DT_ESTIMATION = "estimation"
    const val DT_EDITEUR = "editeur"
    const val DT_ISBN = "ean/isbn"

    /** `…-15161.html` (any slug, with or without `#ed-…`): the album id shared by all its editions. */
    val ID_ALBUM = Regex("""-(\d+)\.html""")
    val ID_EDITION = Regex("""#ed-(\d+)""")

    /** `h2.bdt-ah-sub` own text: `1. Titre`, `HS2. Titre`; a one-shot has no number. */
    val TOME_ET_TITRE = Regex("""([^\s.]{1,8})\s*\.\s*(.*)""")

    // Estimation texts: "de 100 à 150 euros", "moins de 10 euros", "plus de 500 euros", "30 euros", "non coté"
    val NON_COTE = Regex("""non\s+cot""", RegexOption.IGNORE_CASE)
    val COTE_FOURCHETTE = Regex("""de\s+([\d\s]+)\s+à\s+([\d\s]+)""", RegexOption.IGNORE_CASE)
    val COTE_MOINS_DE = Regex("""moins\s+de\s+([\d\s]+)""", RegexOption.IGNORE_CASE)
    val COTE_PLUS_DE = Regex("""plus\s+de\s+([\d\s]+)""", RegexOption.IGNORE_CASE)
    val COTE_SIMPLE = Regex("""^([\d\s]+)\s*(euros?|€)""", RegexOption.IGNORE_CASE)
}
