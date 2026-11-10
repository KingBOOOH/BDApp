package fr.noe.bda.data

import fr.noe.bda.data.bedetheque.BedethequeBloqueException
import fr.noe.bda.data.bedetheque.BedethequeSource
import fr.noe.bda.data.bedetheque.Correspondance
import fr.noe.bda.data.bedetheque.Cote
import fr.noe.bda.data.bedetheque.Edition
import fr.noe.bda.data.bedetheque.Fiche
import fr.noe.bda.data.bedetheque.Selectors
import fr.noe.bda.data.bedetheque.cleSerie
import fr.noe.bda.data.bedetheque.normaliser
import fr.noe.bda.data.bedetheque.trouverEdition
import fr.noe.bda.data.local.Album
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.CoteHistorique
import fr.noe.bda.data.local.CoteStatut
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns what bedetheque says into album rows. Shared by the add flow, the detail screen and
 * the bulk update worker, so they all follow the same rules:
 * - a failure never erases a known cote (status ERREUR, values kept);
 * - a hand-typed cote (MANUELLE) is never overwritten;
 * - a cote from an imported spreadsheet (EXCEL) is only replaced by an actual bedetheque estimation;
 * - a history line is added only when the cote changes;
 * - a scanned album matching one imported without ISBN (same series and tome) completes it
 *   instead of becoming a duplicate.
 */
@Singleton
class Cotes @Inject constructor(
    private val dao: BdaDao,
    private val bedetheque: BedethequeSource,
    private val couvertures: Couvertures,
) {
    private fun maintenant() = System.currentTimeMillis()

    /**
     * Rereads the stored edition, or searches for one if none is linked yet.
     * Throws [BedethequeBloqueException] so a bulk run can stop; any other failure is recorded on the album.
     */
    suspend fun rafraichir(albumId: Long) {
        val album = dao.album(albumId) ?: return
        if (album.coteStatut == CoteStatut.MANUELLE) return
        try {
            val url = album.bdthequeUrl
            if (url != null) {
                val fiche = bedetheque.lireFiche(url)
                val id = Selectors.ID_EDITION.find(url)?.groupValues?.get(1)?.toLongOrNull()
                val edition = fiche.editions.firstOrNull { it.id == id } ?: return echec(album)
                appliquer(album, fiche, edition)
            } else {
                when (val c = chercher(album)) {
                    is Correspondance.Trouvee -> appliquer(album, c.fiche, c.edition)
                    is Correspondance.AChoisir -> sansReponse(album, CoteStatut.A_CHOISIR)
                    is Correspondance.Aucune -> sansReponse(album, CoteStatut.NON_TROUVEE)
                }
            }
        } catch (e: BedethequeBloqueException) {
            throw e
        } catch (e: IOException) {
            echec(album)
        }
    }

    /** ISBN first, then series + tome (albums imported from a spreadsheet), then the title if it is a real one. */
    private suspend fun chercher(album: Album) = if (album.isbn == null && album.tome == null) {
        // A one-shot from the list: its "series" name is its title, the only thing to search by
        bedetheque.trouverEdition(null, album.titre)
    } else {
        bedetheque.trouverEdition(
            album.isbn,
            album.titre.takeUnless { it == album.isbn || normaliser(it) == album.serieNorm },
            album.serie,
            album.tome,
        )
    }

    /** Nothing usable found: an Excel cote stays as it is, anything else gets [statut]. */
    private suspend fun sansReponse(album: Album, statut: CoteStatut) = dao.modifierAlbum(
        album.copy(coteStatut = if (album.coteStatut == CoteStatut.EXCEL) CoteStatut.EXCEL else statut, coteMajLe = maintenant()),
    )

    private suspend fun echec(album: Album) {
        if (album.coteStatut != CoteStatut.EXCEL) dao.modifierAlbum(album.copy(coteStatut = CoteStatut.ERREUR))
    }

    /** The editions to pick from, for an album in A_CHOISIR or one being linked by hand. */
    suspend fun editionsPossibles(album: Album): List<Pair<Fiche, Edition>> =
        when (val c = chercher(album)) {
            is Correspondance.Trouvee -> listOf(c.fiche to c.edition)
            is Correspondance.AChoisir -> c.editions.map { e -> c.fiches.first { e in it.editions } to e }
            is Correspondance.Aucune -> emptyList()
        }

    /** A bedetheque URL pasted by the owner: every edition of that album page. */
    suspend fun editionsDeLaFiche(url: String): List<Pair<Fiche, Edition>> =
        bedetheque.lireFiche(url).let { fiche -> fiche.editions.map { fiche to it } }

    suspend fun lier(albumId: Long, fiche: Fiche, edition: Edition) {
        val album = dao.album(albumId) ?: return
        appliquer(album.copy(coteStatut = CoteStatut.NON_CHERCHEE), fiche, edition)
    }

    suspend fun saisirManuellement(albumId: Long, min: Int?, max: Int?) {
        val album = dao.album(albumId) ?: return
        val texte = when {
            min != null && max != null && min != max -> "de $min à $max euros"
            else -> (min ?: max)?.let { "$it euros" }
        }
        dao.modifierAlbum(
            album.copy(coteMin = min, coteMax = max, coteTexte = texte, coteStatut = CoteStatut.MANUELLE, coteMajLe = maintenant()),
        )
        historiser(albumId, min, max)
    }

    /** Hands a hand-typed cote back to bedetheque. */
    suspend fun reprendreAutomatique(albumId: Long) {
        val album = dao.album(albumId) ?: return
        dao.modifierAlbum(album.copy(coteStatut = CoteStatut.NON_CHERCHEE))
        rafraichir(albumId)
    }

    private suspend fun appliquer(album: Album, fiche: Fiche, edition: Edition) {
        // First link: bedetheque knows the series and tome far better than the ISBN APIs do
        val premierLien = album.bdthequeUrl != edition.url
        if (premierLien && album.isbn != null) jumeauSansIsbn(album, fiche)?.let { return fusionner(album, it, fiche, edition) }
        val cote = edition.cote
        val (min, max, texte, statut) = when {
            cote is Cote.Estimee -> Quatre(cote.minEuros, cote.maxEuros, cote.texte, CoteStatut.TROUVEE)
            // Only a real estimation replaces the spreadsheet figure
            album.coteStatut == CoteStatut.EXCEL -> Quatre(album.coteMin, album.coteMax, album.coteTexte, CoteStatut.EXCEL)
            cote == Cote.NonCotee -> Quatre(null, null, "non coté", CoteStatut.NON_COTEE)
            // The sheet changed shape: keep the last known values
            else -> Quatre(album.coteMin, album.coteMax, album.coteTexte, CoteStatut.ERREUR)
        }
        dao.modifierAlbum(
            album.copy(
                titre = if (premierLien) fiche.titre ?: edition.titre ?: album.titre else album.titre,
                serie = if (premierLien) fiche.serie ?: album.serie else album.serie,
                tome = if (premierLien) fiche.tome ?: album.tome else album.tome,
                editeur = album.editeur ?: edition.editeur,
                bdthequeUrl = edition.url,
                coteMin = min, coteMax = max, coteTexte = texte, coteStatut = statut,
                coteMajLe = maintenant(),
            ),
        )
        // bedetheque's "Nom, Prénom" for everyone, so an author isn't split between two spellings
        if (premierLien && fiche.auteurs.isNotEmpty()) {
            dao.delierAuteurs(album.id)
            dao.lierAuteurs(album.id, fiche.auteurs)
        }
        if (statut != CoteStatut.ERREUR && statut != CoteStatut.EXCEL) historiser(album.id, min, max)
        if (premierLien) edition.couvertureUrl?.let { couvertures.telecharger(album.id, it) }
    }

    /** The album imported from the list that a scan has just identified: same series, same tome, no ISBN yet. */
    private suspend fun jumeauSansIsbn(scanne: Album, fiche: Fiche): Album? {
        val cles = setOfNotNull(fiche.serie, fiche.titre.takeIf { fiche.tome == null }).map(::cleSerie).toSet()
        return dao.sansIsbnAuTome(fiche.tome).firstOrNull { it.id != scanne.id && cleSerie(it.serie ?: it.titre) in cles }
    }

    /** Keeps the imported album (its cote and history) with the scan's ISBN and shelf; drops the scan's row. */
    private suspend fun fusionner(scanne: Album, jumeau: Album, fiche: Fiche, edition: Edition) {
        dao.supprimerAlbum(scanne) // first: the ISBN is unique
        val fusionne = jumeau.copy(
            isbn = scanne.isbn,
            etagereId = scanne.etagereId ?: jumeau.etagereId,
            position = if (scanne.etagereId != null) scanne.position else jumeau.position,
            hauteurCm = jumeau.hauteurCm ?: scanne.hauteurCm,
            epaisseurCm = jumeau.epaisseurCm ?: scanne.epaisseurCm,
            pages = jumeau.pages ?: scanne.pages,
        )
        dao.modifierAlbum(fusionne)
        appliquer(fusionne, fiche, edition)
    }

    private suspend fun historiser(albumId: Long, min: Int?, max: Int?) {
        val derniere = dao.derniereCote(albumId)
        if (derniere == null || derniere.min != min || derniere.max != max) {
            dao.insererHistorique(CoteHistorique(albumId = albumId, min = min, max = max, date = maintenant()))
        }
    }

    private data class Quatre(val min: Int?, val max: Int?, val texte: String?, val statut: CoteStatut)
}
