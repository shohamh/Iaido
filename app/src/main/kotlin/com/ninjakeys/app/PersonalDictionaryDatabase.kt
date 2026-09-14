package com.ninjakeys.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.ninjakeys.core.dictionary.WordEntry

@Entity(tableName = "personal_overrides")
data class PersonalOverrideEntity(
    @androidx.room.PrimaryKey val word: String,
    val boost: Double,
    val uses: Int,
)

@Dao
interface PersonalOverrideDao {
    @Query("SELECT * FROM personal_overrides")
    fun all(): List<PersonalOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(override: PersonalOverrideEntity)

    @Query("DELETE FROM personal_overrides WHERE word = :word")
    fun forget(word: String)

    @Query("DELETE FROM personal_overrides")
    fun reset()
}

@Database(entities = [PersonalOverrideEntity::class], version = 1, exportSchema = false)
abstract class PersonalDictionaryDatabase : RoomDatabase() {
    abstract fun overrides(): PersonalOverrideDao
}

class RoomPersonalDictionaryRepository(
    private val dao: PersonalOverrideDao,
    private val base: List<WordEntry>,
) {
    fun entries(): List<WordEntry> {
        val boosts = dao.all().associate { it.word to it.boost }
        return base.map { entry -> entry.copy(frequency = entry.frequency * (boosts[entry.word] ?: 1.0)) }
    }

    fun reinforce(word: String) {
        val current = dao.all().firstOrNull { it.word == word }
        dao.save(PersonalOverrideEntity(word, (current?.boost ?: 1.0) + 1.0, (current?.uses ?: 0) + 1))
    }

    fun forget(word: String) = dao.forget(word)
    fun reset() = dao.reset()
}
