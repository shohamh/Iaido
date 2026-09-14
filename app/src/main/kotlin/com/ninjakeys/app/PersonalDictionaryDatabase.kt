package com.ninjakeys.app

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(tableName = "personal_ngrams", primaryKeys = ["previousWord", "nextWord"])
data class PersonalNgramOverrideEntity(
    val previousWord: String,
    val nextWord: String,
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

    @Query("SELECT * FROM personal_ngrams")
    fun allNgrams(): List<PersonalNgramOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveNgram(override: PersonalNgramOverrideEntity)

    @Query("DELETE FROM personal_ngrams WHERE previousWord = :word OR nextWord = :word")
    fun forgetNgrams(word: String)

    @Query("DELETE FROM personal_ngrams")
    fun resetNgrams()
}

@Database(entities = [PersonalOverrideEntity::class, PersonalNgramOverrideEntity::class], version = 2, exportSchema = false)
abstract class PersonalDictionaryDatabase : RoomDatabase() {
    abstract fun overrides(): PersonalOverrideDao
}

val PERSONAL_DICTIONARY_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS personal_ngrams (previousWord TEXT NOT NULL, nextWord TEXT NOT NULL, boost REAL NOT NULL, uses INTEGER NOT NULL, PRIMARY KEY(previousWord, nextWord))",
        )
    }
}

class RoomPersonalDictionaryRepository(
    private val dao: PersonalOverrideDao,
    private val base: List<WordEntry>,
) {
    fun entries(): List<WordEntry> {
        val boosts = dao.all().associate { it.word to it.boost }
        return (base.map { it.word } + boosts.keys).distinct().map { word ->
            val baseEntry = base.firstOrNull { it.word == word }
            WordEntry(
                word = word,
                frequency = (baseEntry?.frequency ?: 0.01) * (boosts[word] ?: 1.0),
            )
        }
    }

    fun reinforce(word: String) {
        val current = dao.all().firstOrNull { it.word == word }
        dao.all().filter { it.word != word }.forEach { other ->
            dao.save(other.copy(boost = (other.boost * 0.98).coerceAtLeast(0.1)))
        }
        val uses = current?.uses ?: 0
        val boost = ((current?.boost ?: 1.0) + 1.0 / (uses + 1)).coerceAtMost(32.0)
        dao.save(PersonalOverrideEntity(word, boost, uses + 1))
    }

    fun record(
        signal: com.ninjakeys.core.dictionary.LearningSignal,
        original: String? = null,
        replacement: String,
        previousWord: String? = null,
        nextWord: String? = null,
    ) {
        repeat(if (signal == com.ninjakeys.core.dictionary.LearningSignal.DELETE_RETYPE) 3 else 1) { reinforce(replacement) }
        if (signal != com.ninjakeys.core.dictionary.LearningSignal.EXPLICIT_ADD &&
            original != null && original != replacement
        ) {
            val current = dao.all().firstOrNull { it.word == original }
            dao.save(PersonalOverrideEntity(original, ((current?.boost ?: 1.0) * 0.75).coerceAtLeast(0.1), current?.uses ?: 0))
        }
        if (signal == com.ninjakeys.core.dictionary.LearningSignal.DELETE_RETYPE) {
            previousWord?.let { reinforceNgram(it, replacement) }
            nextWord?.let { reinforceNgram(replacement, it) }
        }
    }

    fun ngramBoost(previousWord: String, nextWord: String): Double =
        dao.allNgrams().firstOrNull { it.previousWord == previousWord && it.nextWord == nextWord }?.boost ?: 1.0

    private fun reinforceNgram(previousWord: String, nextWord: String) {
        val current = dao.allNgrams().firstOrNull { it.previousWord == previousWord && it.nextWord == nextWord }
        val uses = current?.uses ?: 0
        val boost = ((current?.boost ?: 1.0) + 1.0 / (uses + 1)).coerceAtMost(32.0)
        dao.saveNgram(PersonalNgramOverrideEntity(previousWord, nextWord, boost, uses + 1))
    }

    fun forget(word: String) {
        dao.forget(word)
        dao.forgetNgrams(word)
    }

    fun reset() {
        dao.reset()
        dao.resetNgrams()
    }
}
