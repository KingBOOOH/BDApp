package fr.noe.bda.data.local

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import fr.noe.bda.data.bedetheque.normaliser
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BdaDao {

    // Albums

    @Transaction
    @Query("SELECT * FROM album ORDER BY serieNorm, tome, titre")
    abstract fun observerAlbums(): Flow<List<AlbumComplet>>

    @Transaction
    @Query("SELECT * FROM album WHERE id = :id")
    abstract fun observerAlbum(id: Long): Flow<AlbumComplet?>

    @Query("SELECT * FROM album WHERE id = :id")
    abstract suspend fun album(id: Long): Album?

    @Query("SELECT * FROM album WHERE isbn = :isbn")
    abstract suspend fun albumParIsbn(isbn: String): Album?

    @Insert
    protected abstract suspend fun insererAlbum(album: Album): Long

    @Update
    protected abstract suspend fun modifier(album: Album)

    /** Every write goes through here so [Album.serieNorm] always follows [Album.serie]. */
    open suspend fun modifierAlbum(album: Album) = modifier(album.copy(serieNorm = album.serie?.let(::normaliser)))

    @Query("DELETE FROM album_auteur WHERE albumId = :albumId")
    abstract suspend fun delierAuteurs(albumId: Long)

    @Delete
    abstract suspend fun supprimerAlbum(album: Album)

    /** Saves a new album with its authors (created on first use). Returns its id. */
    @Transaction
    open suspend fun ajouter(album: Album, auteurs: List<String>): Long {
        val id = insererAlbum(album.copy(serieNorm = album.serie?.let(::normaliser)))
        lierAuteurs(id, auteurs)
        return id
    }

    @Transaction
    open suspend fun lierAuteurs(albumId: Long, auteurs: List<String>) {
        auteurs.map(String::trim).filter(String::isNotEmpty).distinctBy(::normaliser).forEach { nom ->
            val norm = normaliser(nom)
            insererAuteur(Auteur(nom = nom, nomNorm = norm))
            insererLien(AlbumAuteur(albumId, auteurParNom(norm)))
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insererAuteur(auteur: Auteur): Long

    @Query("SELECT id FROM auteur WHERE nomNorm = :nomNorm")
    protected abstract suspend fun auteurParNom(nomNorm: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insererLien(lien: AlbumAuteur)

    /** Albums imported without ISBN at this tome (`null`: one-shots), for matching a later scan. */
    @Query("SELECT * FROM album WHERE isbn IS NULL AND ((:tome IS NULL AND tome IS NULL) OR tome = :tome)")
    abstract suspend fun sansIsbnAuTome(tome: String?): List<Album>

    @Query("SELECT * FROM auteur ORDER BY nomNorm")
    abstract fun observerAuteurs(): Flow<List<Auteur>>

    @Query("SELECT MIN(serie) FROM album WHERE serie IS NOT NULL GROUP BY serieNorm ORDER BY serieNorm")
    abstract fun observerSeries(): Flow<List<String>>

    // Bulk cote updates: what is left to refresh since [debut], so an interrupted run resumes where it stopped.
    // Hand-typed cotes and editions waiting for the owner's choice are never touched.

    @Query(
        """SELECT id FROM album WHERE coteStatut NOT IN ('MANUELLE', 'A_CHOISIR')
           AND (coteMajLe IS NULL OR coteMajLe < :debut) ORDER BY id""",
    )
    abstract suspend fun aRafraichir(debut: Long): List<Long>

    @Query(
        """SELECT id FROM album WHERE serieNorm = :serieNorm AND coteStatut NOT IN ('MANUELLE', 'A_CHOISIR')
           AND (coteMajLe IS NULL OR coteMajLe < :debut) ORDER BY id""",
    )
    abstract suspend fun aRafraichirSerie(serieNorm: String, debut: Long): List<Long>

    @Query(
        """SELECT a.id FROM album a JOIN album_auteur aa ON aa.albumId = a.id JOIN auteur au ON au.id = aa.auteurId
           WHERE au.nomNorm = :nomNorm AND a.coteStatut NOT IN ('MANUELLE', 'A_CHOISIR')
           AND (a.coteMajLe IS NULL OR a.coteMajLe < :debut) ORDER BY a.id""",
    )
    abstract suspend fun aRafraichirAuteur(nomNorm: String, debut: Long): List<Long>

    @Query("SELECT id FROM album WHERE coteStatut = 'NON_CHERCHEE' ORDER BY id")
    abstract suspend fun nonCherches(): List<Long>

    // Cote history

    @Insert
    abstract suspend fun insererHistorique(ligne: CoteHistorique)

    @Query("SELECT * FROM cote_historique WHERE albumId = :albumId ORDER BY date DESC")
    abstract fun observerHistorique(albumId: Long): Flow<List<CoteHistorique>>

    @Query("SELECT * FROM cote_historique WHERE albumId = :albumId ORDER BY date DESC LIMIT 1")
    abstract suspend fun derniereCote(albumId: Long): CoteHistorique?

    // Backup

    @Query("SELECT * FROM album") abstract suspend fun tousAlbums(): List<Album>
    @Query("SELECT * FROM auteur") abstract suspend fun tousAuteurs(): List<Auteur>
    @Query("SELECT * FROM album_auteur") abstract suspend fun tousLiens(): List<AlbumAuteur>
    @Query("SELECT * FROM cote_historique") abstract suspend fun toutHistorique(): List<CoteHistorique>

    @Query("DELETE FROM album") protected abstract suspend fun viderAlbums()
    @Query("DELETE FROM auteur") protected abstract suspend fun viderAuteurs()

    @Insert protected abstract suspend fun insererAlbums(l: List<Album>)
    @Insert protected abstract suspend fun insererAuteurs(l: List<Auteur>)
    @Insert protected abstract suspend fun insererLiens(l: List<AlbumAuteur>)
    @Insert protected abstract suspend fun insererHistoriques(l: List<CoteHistorique>)

    /**
     * Adds a file's albums to the collection, all or nothing: new ids, authors matched by
     * name. An ISBN already present is skipped.
     * Returns how many albums were added.
     */
    @Transaction
    open suspend fun fusionner(albums: List<Album>, auteurs: Map<Long, List<String>>, historique: List<CoteHistorique>): Int {
        val parAlbum = historique.groupBy { it.albumId }
        var ajoutes = 0
        for (album in albums) {
            if (album.isbn != null && albumParIsbn(album.isbn) != null) continue
            val id = ajouter(album.copy(id = 0), auteurs[album.id].orEmpty())
            parAlbum[album.id].orEmpty().forEach { insererHistorique(it.copy(id = 0, albumId = id)) }
            ajoutes++
        }
        return ajoutes
    }

    /** Replaces the whole collection, all or nothing. Cascades empty the child tables. */
    @Transaction
    open suspend fun remplacerTout(
        albums: List<Album>, auteurs: List<Auteur>, liens: List<AlbumAuteur>, historique: List<CoteHistorique>,
    ) {
        viderAlbums()
        viderAuteurs()
        insererAlbums(albums)
        insererAuteurs(auteurs)
        insererLiens(liens)
        insererHistoriques(historique)
    }
}
