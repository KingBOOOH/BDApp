package fr.noe.bda.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Junction
import androidx.room3.PrimaryKey
import androidx.room3.Relation
import kotlinx.serialization.Serializable

// The entities double as the backup format (data/backup), hence @Serializable.

/** Which wall of the room a piece of furniture stands against, for the U-shaped library view. */
enum class Mur(val libelle: String) { GAUCHE("gauche"), FOND("fond"), DROIT("droit") }

enum class Forme(val libelle: String) { COLONNE("Colonne"), ETROITE("Étroite"), ANGLE("Angle"), BAS("Meuble bas") }

enum class TypeEtagere(val libelle: String) {
    BD("BD"),
    /** Figurines and objects: drawn as decor, not offered as a place for albums. */
    FIGURINES("Figurines"),
    /** Behind a solid door: albums are there but hidden until the door is opened. */
    FERMEE("Fermée"),
}

@Serializable
@Entity(tableName = "meuble")
data class Meuble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val ordre: Int,
    @ColumnInfo(defaultValue = "FOND") val mur: Mur = Mur.FOND,
    @ColumnInfo(defaultValue = "COLONNE") val forme: Forme = Forme.COLONNE,
    @ColumnInfo(defaultValue = "0") val vitre: Boolean = false,
)

@Serializable
@Entity(
    tableName = "etagere",
    foreignKeys = [ForeignKey(Meuble::class, ["id"], ["meubleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("meubleId")],
)
data class Etagere(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meubleId: Long,
    val nom: String,
    val ordre: Int,
    @ColumnInfo(defaultValue = "BD") val type: TypeEtagere = TypeEtagere.BD,
)

enum class CoteStatut {
    /** Not looked up yet (just scanned, waiting for the bedetheque queue). */
    NON_CHERCHEE,
    TROUVEE,
    /** The edition is on bedetheque, which gives it no estimation. */
    NON_COTEE,
    NON_TROUVEE,
    /** Several editions share the ISBN with different cotes: the owner picks one. */
    A_CHOISIR,
    /** Typed by hand: never overwritten by an update. */
    MANUELLE,
    /** From the parents' spreadsheet: replaced only by an actual bedetheque estimation. */
    EXCEL,
    /** Last refresh failed; the previous cote is kept. */
    ERREUR,
}

@Serializable
@Entity(
    tableName = "album",
    foreignKeys = [ForeignKey(Etagere::class, ["id"], ["etagereId"], onDelete = ForeignKey.SET_NULL)],
    indices = [
        Index("isbn", unique = true),
        Index("serieNorm"),
        Index("etagereId", "position"),
        Index("coteStatut"),
    ],
)
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISBN-13; `null` for old albums without a barcode. */
    val isbn: String? = null,
    val titre: String,
    val serie: String? = null,
    /** [serie] through `normaliser`, for grouping and bulk updates regardless of case and accents. */
    val serieNorm: String? = null,
    /** Text: `3`, `HS`, `INT 2`. */
    val tome: String? = null,
    val editeur: String? = null,
    val couvertureUrl: String? = null,
    /** Local copy under filesDir/covers, so the library shows offline. */
    val couvertureFichier: String? = null,
    val couleurDos: Int? = null,
    val hauteurCm: Float? = null,
    val epaisseurCm: Float? = null,
    val pages: Int? = null,
    /** Album page + `#ed-<id>`: refreshes read it directly, without searching again. */
    val bdthequeUrl: String? = null,
    val coteMin: Int? = null,
    val coteMax: Int? = null,
    val coteTexte: String? = null,
    val coteMajLe: Long? = null,
    val coteStatut: CoteStatut = CoteStatut.NON_CHERCHEE,
    val etagereId: Long? = null,
    /** Order from left to right on the shelf. */
    val position: Int = 0,
    val ajouteLe: Long = System.currentTimeMillis(),
    /** Signed by an author: worth more, and worth knowing before lending it. */
    @ColumnInfo(defaultValue = "0") val dedicace: Boolean = false,
) {
    /** One number per album for totals and sorting: the middle of the range, or its only bound. */
    val coteEstimee: Int?
        get() = when {
            coteMin != null && coteMax != null -> (coteMin + coteMax) / 2
            else -> coteMin ?: coteMax
        }
}

@Serializable
@Entity(tableName = "auteur", indices = [Index("nomNorm", unique = true)])
data class Auteur(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val nomNorm: String,
)

@Serializable
@Entity(
    tableName = "album_auteur",
    primaryKeys = ["albumId", "auteurId"],
    foreignKeys = [
        ForeignKey(Album::class, ["id"], ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Auteur::class, ["id"], ["auteurId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("auteurId")],
)
data class AlbumAuteur(val albumId: Long, val auteurId: Long)

@Serializable
@Entity(
    tableName = "cote_historique",
    foreignKeys = [ForeignKey(Album::class, ["id"], ["albumId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("albumId")],
)
data class CoteHistorique(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val min: Int?,
    val max: Int?,
    val date: Long,
)

data class AlbumComplet(
    @Embedded val album: Album,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["id"],
        associateBy = Junction(AlbumAuteur::class, parentColumns = ["albumId"], entityColumns = ["auteurId"]),
    )
    val auteurs: List<Auteur>,
)

data class MeubleComplet(
    @Embedded val meuble: Meuble,
    @Relation(parentColumns = ["id"], entityColumns = ["meubleId"])
    val etageres: List<Etagere>,
)
