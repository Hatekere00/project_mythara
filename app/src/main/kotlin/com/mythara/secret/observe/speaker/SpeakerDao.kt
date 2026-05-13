package com.mythara.secret.observe.speaker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM enrolled_speakers ORDER BY enrolled_at_ms DESC")
    fun observeAll(): Flow<List<EnrolledSpeaker>>

    @Query("SELECT * FROM enrolled_speakers ORDER BY enrolled_at_ms DESC")
    suspend fun listAll(): List<EnrolledSpeaker>

    @Query("SELECT * FROM enrolled_speakers WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): EnrolledSpeaker?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EnrolledSpeaker)

    @Update
    suspend fun update(entity: EnrolledSpeaker)

    @Query("DELETE FROM enrolled_speakers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM enrolled_speakers WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM enrolled_speakers")
    suspend fun clear()

    @Query("UPDATE enrolled_speakers SET last_matched_at_ms = :ts, match_count = match_count + 1 WHERE id = :id")
    suspend fun bumpMatch(id: String, ts: Long)
}
