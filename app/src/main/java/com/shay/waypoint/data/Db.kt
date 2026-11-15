package com.shay.waypoint.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** One row of any sheet tab. [data] is a JSON object holding every column as text. */
@Entity(tableName = "rows", primaryKeys = ["tbl", "id"])
data class RowEntity(
    val tbl: String,
    val id: String,
    val data: String,
    val updatedAt: String,
    val dirty: Boolean,
) {
    fun map(): Map<String, String> = jsonToMap(data)
}

/** Local hard deletes for tables that have no `deleted` column; pushed on next sync. */
@Entity(tableName = "tombstones", primaryKeys = ["tbl", "id"])
data class Tombstone(val tbl: String, val id: String)

@Entity(tableName = "sync_log")
data class SyncLogEntry(
    @PrimaryKey(autoGenerate = true) val n: Long = 0,
    val time: Long,
    val level: String,
    val message: String,
)

/** Local-only food memory: custom foods, favourites and recents. */
@Entity(tableName = "foods")
data class FoodItem(
    @PrimaryKey val name: String,
    val kcal: Double,
    val protein: Double,
    val favourite: Boolean = false,
    val lastUsed: Long = 0,
    val custom: Boolean = false,
)

@Dao
interface WDao {
    @Query("SELECT * FROM rows WHERE tbl = :tbl")
    fun observe(tbl: String): Flow<List<RowEntity>>

    @Query("SELECT * FROM rows WHERE tbl = :tbl")
    suspend fun all(tbl: String): List<RowEntity>

    @Query("SELECT * FROM rows WHERE tbl = :tbl AND id = :id")
    suspend fun get(tbl: String, id: String): RowEntity?

    @Upsert
    suspend fun upsert(r: RowEntity)

    @Query("DELETE FROM rows WHERE tbl = :tbl AND id = :id")
    suspend fun delete(tbl: String, id: String)

    @Query("DELETE FROM rows")
    suspend fun clearRows()

    @Query("DELETE FROM rows WHERE tbl = :tbl")
    suspend fun clearTable(tbl: String)

    /** Only if the row is still exactly what was pushed (not edited meanwhile). */
    @Query("UPDATE rows SET dirty = 0 WHERE tbl = :tbl AND id = :id AND data = :data")
    suspend fun clearDirtyIf(tbl: String, id: String, data: String)

    @Query("DELETE FROM rows WHERE tbl = :tbl AND id = :id AND data = :data")
    suspend fun deleteIf(tbl: String, id: String, data: String)

    @Query("DELETE FROM tombstones WHERE tbl = :tbl AND id = :id")
    suspend fun deleteTombstone(tbl: String, id: String)

    @Query("UPDATE rows SET dirty = 0 WHERE tbl = :tbl")
    suspend fun clearDirty(tbl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstone(t: Tombstone)

    @Query("SELECT id FROM tombstones WHERE tbl = :tbl")
    suspend fun tombstones(tbl: String): List<String>

    @Query("DELETE FROM tombstones WHERE tbl = :tbl")
    suspend fun clearTombstones(tbl: String)

    @Query("DELETE FROM tombstones")
    suspend fun clearAllTombstones()

    @Insert
    suspend fun log(e: SyncLogEntry)

    @Query("SELECT * FROM sync_log ORDER BY n DESC LIMIT 150")
    fun observeLog(): Flow<List<SyncLogEntry>>

    @Query("DELETE FROM sync_log WHERE n < (SELECT MAX(n) FROM sync_log) - 400")
    suspend fun trimLog()

    @Query("SELECT * FROM foods")
    fun observeFoods(): Flow<List<FoodItem>>

    @Query("SELECT * FROM foods WHERE name = :name")
    suspend fun food(name: String): FoodItem?

    @Upsert
    suspend fun upsertFood(f: FoodItem)

    @Query("DELETE FROM foods WHERE name = :name")
    suspend fun deleteFood(name: String)
}

@Database(
    entities = [RowEntity::class, Tombstone::class, SyncLogEntry::class, FoodItem::class],
    version = 1,
    exportSchema = false,
)
abstract class WDb : RoomDatabase() {
    abstract fun dao(): WDao
}

fun jsonToMap(s: String): Map<String, String> {
    val o = JSONObject(s)
    val m = LinkedHashMap<String, String>()
    for (k in o.keys()) m[k] = jsonStr(o.opt(k))
    return m
}

fun jsonStr(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is Boolean -> if (v) "TRUE" else "FALSE"
    is Double -> fmtNum(v)
    else -> v.toString()
}

fun Map<String, String>.toJson(): String = JSONObject(this as Map<*, *>).toString()

fun Map<String, String>.s(k: String): String = this[k] ?: ""
