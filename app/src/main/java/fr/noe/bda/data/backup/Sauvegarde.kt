package fr.noe.bda.data.backup

import fr.noe.bda.data.local.Album
import fr.noe.bda.data.local.AlbumAuteur
import fr.noe.bda.data.local.Auteur
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.CoteHistorique
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The whole collection as one JSON file. Cover images are not included, only their URLs. */
@Serializable
data class Contenu(
    val version: Int = 1,
    val exporteLe: Long,
    val albums: List<Album>,
    val auteurs: List<Auteur>,
    val albumsAuteurs: List<AlbumAuteur>,
    val historique: List<CoteHistorique>,
)

@Singleton
class Sauvegarde @Inject constructor(private val dao: BdaDao) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun exporter(): String = json.encodeToString(
        Contenu(
            exporteLe = System.currentTimeMillis(),
            albums = dao.tousAlbums(),
            auteurs = dao.tousAuteurs(),
            albumsAuteurs = dao.tousLiens(),
            historique = dao.toutHistorique(),
        ),
    )

    /** Parses first, so an invalid file throws before anything is replaced. */
    fun lire(texte: String): Contenu = json.decodeFromString(texte)

    /** Adds the file's albums without touching what is already there. Returns how many were added. */
    suspend fun ajouter(contenu: Contenu): Int {
        val noms = contenu.auteurs.associate { it.id to it.nom }
        val auteurs = contenu.albumsAuteurs.groupBy({ it.albumId }, { noms[it.auteurId].orEmpty() })
        return dao.fusionner(contenu.albums, auteurs, contenu.historique)
    }

    suspend fun restaurer(contenu: Contenu) {
        dao.remplacerTout(
            // Paths from another phone mean nothing here; covers then load from their URL, spine colours are kept
            contenu.albums.map { it.copy(couvertureFichier = null) },
            contenu.auteurs, contenu.albumsAuteurs, contenu.historique,
        )
    }
}
