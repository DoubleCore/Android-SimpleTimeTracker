package com.example.util.simpletimetracker.data_local.recordType

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecordTypeDao {

    @Query("SELECT * FROM recordTypes")
    suspend fun getAll(): List<RecordTypeDBO>

    @Query("SELECT * FROM recordTypes WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): RecordTypeDBO?

    @Query("SELECT * FROM recordTypes WHERE name = :name")
    suspend fun get(name: String): List<RecordTypeDBO>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecordTypeDBO): Long

    // UPDATE rather than REPLACE: retain the same row/FKs and all historical sessions.
    @Query("UPDATE recordTypes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE recordTypes SET hidden = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE recordTypes SET hidden = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM recordTypes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recordTypes")
    suspend fun clear()
}