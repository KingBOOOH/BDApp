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

    // Locations

    @Transaction
    @Query("SELECT * FROM meuble ORDER BY ordre")
    abstract fun observerMeubles(): Flow<List<MeubleComplet>>

    @Insert
    abstract suspend fun insererMeuble(meuble: Meuble): Long

    @Update
    abstract suspend fun modifierMeubles(vararg meubles: Meuble)

    @Delete
    abstract suspend fun supprimerMeuble(meuble: Meuble)

    @Insert
    abstract suspend fun insererEtagere(etagere: Etagere): Long

    @Update
    abstract suspend fun modifierEtageres(vararg etageres: Etagere)

    @Delete
    abstract suspend fun supprimerEtagere(etagere: Etagere)

    @Query("SELECT COALESCE(MAX(ordre) + 1, 0) FROM meuble")
    abstract suspend fun ordreMeubleSuivant(): Int

    @Query("SELECT COALESCE(MAX(ordre) + 1, 0) FROM etagere WHERE meubleId = :meubleId")
    abstract suspend fun ordreEtagereSuivant(meubleId: Long): Int

    /** Where the last album went: the add screen starts there, for scanning a whole shelf in a row. */
    @Query("SELECT etagereId FROM album WHERE etagereId IS NOT NULL ORDER BY ajouteLe DESC, id DESC LIMIT 1")
    abstract suspend fun derniereEtagere(): Long?

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM album WHERE etagereId = :etagereId")
    abstract suspend fun positionSuivante(etagereId: Long): Int

    /** Puts the album at the end of [etagereId] (or nowhere). */
    @Transaction
    open suspend fun deplacer(albumId: Long, etagereId: Long?) {
        val album = album(albumId) ?: return
        modifierAlbum(album.copy(etagereId = etagereId, position = etagereId?.let { positionSuivante(it) } ?: 0))
    }

    @Query("SELECT * FROM album WHERE etagereId = :etagereId ORDER BY position")
    abstract suspend fun albumsDe(etagereId: Long): List<Album>

    /** Swaps the album with its left (-1) or right (+1) neighbour on the shelf. */
    @Transaction
    open suspend fun decaler(albumId: Long, sens: Int) {
        val album = album(albumId) ?: return
        val rangee = albumsDe(album.etagereId ?: return)
        val i = rangee.indexOfFirst { it.id == albumId }
        val voisin = rangee.getOrNull(i + sens) ?: return
        modifierAlbum(album.copy(position = voisin.position))
        modifierAlbum(voisin.copy(position = album.position))
    }

    // Backup

    @Query("SELECT * FROM meuble") abstract suspend fun tousMeubles(): List<Meuble>
    @Query("SELECT * FROM etagere") abstract suspend fun toutesEtageres(): List<Etagere>
    @Query("SELECT * FROM album") abstract suspend fun tousAlbums(): List<Album>
    @Query("SELECT * FROM auteur") abstract suspend fun tousAuteurs(): List<Auteur>
    @Query("SELECT * FROM album_auteur") abstract suspend fun tousLiens(): List<AlbumAuteur>
    @Query("SELECT * FROM cote_historique") abstract suspend fun toutHistorique(): List<CoteHistorique>

    @Query("DELETE FROM meuble") protected abstract suspend fun viderMeubles()
    @Query("DELETE FROM album") protected abstract suspend fun viderAlbums()
    @Query("DELETE FROM auteur") protected abstract suspend fun viderAuteurs()

    @Insert protected abstract suspend fun insererMeubles(l: List<Meuble>)
    @Insert protected abstract suspend fun insererEtageres(l: List<Etagere>)
    @Insert protected abstract suspend fun insererAlbums(l: List<Album>)
    @Insert protected abstract suspend fun insererAuteurs(l: List<Auteur>)
    @Insert protected abstract suspend fun insererLiens(l: List<AlbumAuteur>)
    @Insert protected abstract suspend fun insererHistoriques(l: List<CoteHistorique>)

    /**
     * Adds a file's albums to the collection, all or nothing: new ids, no shelf (the file's
     * furniture isn't brought in), authors matched by name. An ISBN already present is skipped.
     * Returns how many albums were added.
     */
    @Transaction
    open suspend fun fusionner(albums: List<Album>, auteurs: Map<Long, List<String>>, historique: List<CoteHistorique>): Int {
        val parAlbum = historique.groupBy { it.albumId }
        var ajoutes = 0
        for (album in albums) {
            if (album.isbn != null && albumParIsbn(album.isbn) != null) continue
            val id = ajouter(album.copy(id = 0, etagereId = null, position = 0), auteurs[album.id].orEmpty())
            parAlbum[album.id].orEmpty().forEach { insererHistorique(it.copy(id = 0, albumId = id)) }
            ajoutes++
        }
        return ajoutes
    }

    /** Replaces the whole collection, all or nothing. Cascades empty the child tables. */
    @Transaction
    open suspend fun remplacerTout(
        meubles: List<Meuble>, etageres: List<Etagere>, albums: List<Album>,
        auteurs: List<Auteur>, liens: List<AlbumAuteur>, historique: List<CoteHistorique>,
    ) {
        viderAlbums()
        viderAuteurs()
        viderMeubles()
        insererMeubles(meubles)
        insererEtageres(etageres)
        insererAlbums(albums)
        insererAuteurs(auteurs)
        insererLiens(liens)
        insererHistoriques(historique)
    }
}
