package com.choice.autotap.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A macro row. The full [com.choice.autotap.model.Macro] is stored as JSON in [json]. */
@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val macroId: Long,
    val macroName: String,
    val startedAt: Long,
    val endedAt: Long,
    val endReason: String,
    val json: String,
)

/** Saved texts for "Paste text" steps. */
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val text: String,
)

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros ORDER BY updatedAt DESC")
    suspend fun getAll(): List<MacroEntity>

    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun get(id: Long): MacroEntity?

    @Insert
    suspend fun insert(entity: MacroEntity): Long

    @Update
    suspend fun update(entity: MacroEntity)

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RunLogDao {
    @Query("SELECT * FROM run_logs ORDER BY startedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<RunLogEntity>>

    @Insert
    suspend fun insert(entity: RunLogEntity): Long

    @Query("DELETE FROM run_logs")
    suspend fun clear()
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Insert
    suspend fun insert(entity: SnippetEntity): Long

    @Update
    suspend fun update(entity: SnippetEntity)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [MacroEntity::class, RunLogEntity::class, SnippetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao
    abstract fun runLogDao(): RunLogDao
    abstract fun snippetDao(): SnippetDao
}
