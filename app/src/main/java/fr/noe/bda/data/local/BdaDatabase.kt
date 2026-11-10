package fr.noe.bda.data.local

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [Meuble::class, Etagere::class, Album::class, Auteur::class, AlbumAuteur::class, CoteHistorique::class],
    version = 3,
    exportSchema = true,
    // 2: wall, shape and glass doors for furniture, a type for shelves. 3: signed albums
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class BdaDatabase : RoomDatabase() {
    abstract fun dao(): BdaDao
}
