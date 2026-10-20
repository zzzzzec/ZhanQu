package dev.zhanqu.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class StoredNote(
 @PrimaryKey val id: String,
 val title: String,
 val author: String,
 val sourcePackage: String,
 val capturedAt: Long,
 val status: String,
 val rawJson: String,
 val editedText: String,
 @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false
)
@Entity(tableName = "metadata")
data class StoreMetadata(@PrimaryKey val key: String, val value: Long)
@Dao
interface NoteDao {
 @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR editedText LIKE '%' || :query || '%' ORDER BY capturedAt DESC")
 fun search(query: String): Flow<List<StoredNote>>
 @Query("SELECT * FROM notes WHERE id = :id") suspend fun find(id: String): StoredNote?
 @Transaction suspend fun saveCheckpoint(note: StoredNote) {
  if (note.capturedAt <= (lastClear() ?: 0L)) return
  val old = find(note.id)
  save(if (old?.userEdited == true) note.copy(editedText = old.editedText, userEdited = true) else note)
 }
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(note: StoredNote)
 @Query("UPDATE notes SET editedText = :text, userEdited = 1 WHERE id = :id") suspend fun saveEdit(id: String, text: String)
 @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: String)
 @Query("SELECT value FROM metadata WHERE `key` = 'clearedAt'") suspend fun lastClear(): Long?
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun metadata(value: StoreMetadata)
 @Query("DELETE FROM notes") suspend fun clearRows()
 @Transaction suspend fun clear() { metadata(StoreMetadata("clearedAt", System.currentTimeMillis())); clearRows() }
}
@Database(entities = [StoredNote::class, StoreMetadata::class], version = 2, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
 abstract fun notes(): NoteDao
 companion object {
  @Volatile private var instance: NoteDatabase? = null
  fun get(context: Context): NoteDatabase = instance ?: synchronized(this) {
   instance ?: Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, "zhanqu.db").addMigrations(object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
     val columns = mutableSetOf<String>()
     db.query("PRAGMA table_info(notes)").use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
     if ("userEdited" !in columns) db.execSQL("ALTER TABLE notes ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
     db.execSQL("CREATE TABLE IF NOT EXISTS metadata (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))")
    }
   }).build().also { instance = it }
  }
 }
}
