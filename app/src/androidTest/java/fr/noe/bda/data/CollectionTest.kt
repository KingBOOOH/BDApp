package fr.noe.bda.data

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.noe.bda.data.backup.Sauvegarde
import fr.noe.bda.data.bedetheque.BedethequeClient
import fr.noe.bda.data.bedetheque.BedethequeSource
import fr.noe.bda.data.bedetheque.Candidat
import fr.noe.bda.data.bedetheque.Cote
import fr.noe.bda.data.bedetheque.Edition
import fr.noe.bda.data.bedetheque.Fiche
import fr.noe.bda.data.local.Album
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.BdaDatabase
import fr.noe.bda.data.local.CoteStatut
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** The rules the add flow, the detail screen and the bulk worker rely on, against a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class CollectionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var base: BdaDatabase
    private lateinit var dao: BdaDao
    private lateinit var cotes: Cotes

    /** What the fake bedetheque answers; tests change it between calls. */
    private var cote: Cote = Cote.Estimee(10, 15, "de 10 à 15 euros")
    private var panne = false

    private val fiche get() = Fiche(
        url = URL, serie = "Zombillénium", tome = "1", titre = "Gretchen", auteurs = listOf("Pins, Arthur de"),
        editions = listOf(Edition(1, "$URL#ed-1", "Tome 1", "Gretchen", "08/2010", "Dupuis", ISBN, cote, null, null)),
    )

    private val source = object : BedethequeSource {
        override suspend fun rechercherParIsbn(isbn13: String) =
            if (panne) throw IOException("hors ligne") else listOf(Candidat(URL, "Zombillénium", "1", "Gretchen", null, null))
        override suspend fun rechercherParTitre(titre: String, serie: String?) = emptyList<Candidat>()
        override suspend fun rechercherParSerie(serie: String) = serieFactice(serie)
        override suspend fun lireFiche(url: String) = if (panne) throw IOException("hors ligne") else fiche
    }

    private fun serieFactice(@Suppress("UNUSED_PARAMETER") serie: String) = emptyList<Candidat>()

    @Before
    fun ouvrir() {
        base = Room.inMemoryDatabaseBuilder(context, BdaDatabase::class.java).build()
        dao = base.dao()
        val http = OkHttpClient()
        cotes = Cotes(dao, source, Couvertures(context, http, BedethequeClient(http), dao))
    }

    @After
    fun fermer() = base.close()

    private suspend fun ajouter(titre: String = ISBN, auteurs: List<String> = emptyList()) =
        dao.ajouter(Album(isbn = ISBN, titre = titre), auteurs)

    @Test
    fun le_premier_lien_nomme_l_album_et_l_historique_ne_change_qu_avec_la_cote() = runTest {
        val id = ajouter()

        cotes.rafraichir(id)
        cotes.rafraichir(id)

        val album = dao.album(id)!!
        assertEquals("Gretchen", album.titre)
        assertEquals("Zombillénium", album.serie)
        assertEquals("zombillenium", album.serieNorm)
        assertEquals("1", album.tome)
        assertEquals(CoteStatut.TROUVEE, album.coteStatut)
        assertEquals("$URL#ed-1", album.bdthequeUrl)
        assertEquals(listOf("Pins, Arthur de"), dao.observerAlbum(id).first()!!.auteurs.map { it.nom })
        assertEquals(1, dao.observerHistorique(id).first().size)

        cote = Cote.Estimee(20, 25, "de 20 à 25 euros")
        cotes.rafraichir(id)
        assertEquals(2, dao.observerHistorique(id).first().size)
    }

    @Test
    fun les_auteurs_de_bdtheque_remplacent_ceux_du_lookup() = runTest {
        val id = ajouter(auteurs = listOf("De Pins"))

        cotes.rafraichir(id)

        assertEquals(listOf("Pins, Arthur de"), dao.observerAlbum(id).first()!!.auteurs.map { it.nom })
    }

    @Test
    fun une_cote_de_la_liste_n_est_remplacee_que_par_une_vraie_cote() = runTest {
        val id = dao.ajouter(Album(isbn = ISBN, titre = "Zombillénium", coteStatut = CoteStatut.EXCEL, coteMin = 50, coteMax = 60), emptyList())
        cote = Cote.NonCotee

        cotes.rafraichir(id)

        dao.album(id)!!.let {
            assertEquals(CoteStatut.EXCEL, it.coteStatut)
            assertEquals(50, it.coteMin)
            assertEquals("$URL#ed-1", it.bdthequeUrl)
        }

        cote = Cote.Estimee(10, 15, "de 10 à 15 euros")
        cotes.rafraichir(id)

        assertEquals(CoteStatut.TROUVEE, dao.album(id)!!.coteStatut)
        assertEquals(10, dao.album(id)!!.coteMin)
    }

    @Test
    fun un_scan_complete_l_album_importe_au_lieu_de_le_doubler() = runTest {
        val importe = dao.ajouter(Album(titre = "zombillenium", serie = "zombillenium", tome = "1", coteStatut = CoteStatut.EXCEL, coteMin = 50, coteMax = 60), emptyList())
        val scanne = dao.ajouter(Album(isbn = ISBN, titre = ISBN), emptyList())

        cotes.rafraichir(scanne)

        assertEquals(null, dao.album(scanne))
        dao.album(importe)!!.let {
            assertEquals(ISBN, it.isbn)
            assertEquals("Gretchen", it.titre)
            assertEquals(CoteStatut.TROUVEE, it.coteStatut)
        }
    }

    @Test
    fun importer_en_ajout_garde_la_collection_et_saute_les_isbn_connus() = runTest {
        ajouter()
        val sauvegarde = Sauvegarde(dao)
        val fichier = sauvegarde.lire(sauvegarde.exporter()).let { c ->
            c.copy(albums = c.albums + Album(id = 999, titre = "Alix", serie = "Alix", tome = "1"))
        }

        assertEquals(1, sauvegarde.ajouter(fichier))
        assertEquals(2, dao.tousAlbums().size)
    }

    @Test
    fun une_panne_garde_la_derniere_cote_connue() = runTest {
        val id = ajouter()
        cotes.rafraichir(id)

        panne = true
        cotes.rafraichir(id)

        val album = dao.album(id)!!
        assertEquals(CoteStatut.ERREUR, album.coteStatut)
        assertEquals(10, album.coteMin)
        assertEquals(15, album.coteMax)
    }

    @Test
    fun une_cote_saisie_a_la_main_n_est_jamais_ecrasee() = runTest {
        val id = ajouter()
        cotes.saisirManuellement(id, 50, 50)

        cotes.rafraichir(id)

        assertEquals(CoteStatut.MANUELLE, dao.album(id)!!.coteStatut)
        assertEquals(50, dao.album(id)!!.coteMin)
    }

    @Test
    fun une_mise_a_jour_interrompue_reprend_les_albums_restants() = runTest {
        val a = ajouter()
        val b = dao.ajouter(Album(isbn = "9782205049657", titre = "Blacksad", serie = "Blacksad"), listOf("Guarnido, Juanjo"))
        val debut = System.currentTimeMillis()
        assertEquals(listOf(a, b), dao.aRafraichir(debut))

        cotes.rafraichir(a) // done before the "interruption"

        assertEquals(listOf(b), dao.aRafraichir(debut))
        assertEquals(listOf(b), dao.aRafraichirSerie("blacksad", debut))
        assertEquals(listOf(b), dao.aRafraichirAuteur("guarnido, juanjo", debut))
    }

    @Test
    fun une_sauvegarde_restauree_redonne_la_meme_collection() = runTest {
        val id = ajouter(auteurs = listOf("Bablet, Mathieu"))
        cotes.rafraichir(id)
        val sauvegarde = Sauvegarde(dao)
        val avant = sauvegarde.lire(sauvegarde.exporter())

        dao.supprimerAlbum(dao.album(id)!!)
        sauvegarde.restaurer(avant)

        val apres = sauvegarde.lire(sauvegarde.exporter())
        assertEquals(avant.copy(exporteLe = 0), apres.copy(exporteLe = 0))
    }

    @Test
    fun une_ancienne_sauvegarde_avec_meubles_s_importe_encore() = runTest {
        val ancienne = """{"version":1,"exporteLe":0,
            "meubles":[{"id":1,"nom":"Salon","ordre":0}],"etageres":[{"id":1,"meubleId":1,"nom":"Haut","ordre":0}],
            "albums":[{"id":1,"titre":"Gretchen","serie":"Zombillénium","tome":"1","etagereId":1,"position":0,"coteStatut":"TROUVEE","coteMin":10,"coteMax":15}],
            "auteurs":[],"albumsAuteurs":[],"historique":[]}"""
        val sauvegarde = Sauvegarde(dao)

        sauvegarde.restaurer(sauvegarde.lire(ancienne))

        assertEquals(listOf("Gretchen"), dao.tousAlbums().map { it.titre })
    }

    companion object {
        const val ISBN = "9782800147215"
        const val URL = "https://www.bedetheque.com/BD-Zombillenium-Tome-1-Gretchen-113245.html"
    }
}
