package fr.noe.bda.data.local

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.DeleteColumn
import androidx.room3.DeleteTable
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec

@Database(
    entities = [Album::class, Auteur::class, AlbumAuteur::class, CoteHistorique::class],
    version = 4,
    exportSchema = true,
    // 2: wall, shape and glass doors for furniture, a type for shelves. 3: signed albums.
    // 4: furniture, shelves and album positions removed.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = SansEmplacements::class),
    ],
)
abstract class BdaDatabase : RoomDatabase() {
    abstract fun dao(): BdaDao
}

@DeleteTable(tableName = "etagere")
@DeleteTable(tableName = "meuble")
@DeleteColumn(tableName = "album", columnName = "etagereId")
@DeleteColumn(tableName = "album", columnName = "position")
class SansEmplacements : AutoMigrationSpec
