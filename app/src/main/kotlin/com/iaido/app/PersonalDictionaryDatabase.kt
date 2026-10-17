package com.iaido.app

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.iaido.core.dictionary.PersonalLearningPolicy
import com.iaido.core.dictionary.WordEntry

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
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS personal_ngrams (previousWord TEXT NOT NULL, nextWord TEXT NOT NULL, boost REAL NOT NULL, uses INTEGER NOT NULL, PRIMARY KEY(previousWord, nextWord))",
        )
    }
}

class RoomPersonalDictionaryRepository(
    private val dao: PersonalOverrideDao,
    private val base: List<WordEntry>,
) {
    private val baseByWord = base.associateBy { it.word.lowercase() }

    fun entries(): List<WordEntry> {
        val boosts = dao.all()
            .groupBy { it.word.lowercase() }
            .mapValues { (_, rows) -> rows.maxBy { it.boost } }
        val customWords = boosts.keys.filter { word ->
            word !in baseByWord && boosts.getValue(word).boost >= 1.0 && word.none { it.isWhitespace() }
        }
        return (base.map { it.word.lowercase() } + customWords).distinct().map { word ->
            val baseEntry = baseByWord[word]
            WordEntry(
                word = word,
                frequency = (baseEntry?.frequency ?: PersonalLearningPolicy.CUSTOM_WORD_BASE_FREQUENCY) *
                    (boosts[word]?.boost ?: 1.0).coerceAtMost(PersonalLearningPolicy.DEFAULT_MAX_BOOST),
            )
        }
    }

    fun reinforce(word: String) {
        val normalizedWord = word.lowercase()
        val current = dao.all().filter { it.word.lowercase() == normalizedWord }.maxByOrNull { it.boost }
        dao.all().filter { it.word.lowercase() != normalizedWord }.forEach { other ->
            dao.save(other.copy(boost = (other.boost * 0.98).coerceAtLeast(PersonalLearningPolicy.MIN_BOOST)))
        }
        val uses = current?.uses ?: 0
        val boost = ((current?.boost ?: 1.0) + 1.0 / (uses + 1)).coerceAtMost(PersonalLearningPolicy.DEFAULT_MAX_BOOST)
        dao.save(PersonalOverrideEntity(normalizedWord, boost, uses + 1))
    }

    fun record(
        signal: com.iaido.core.dictionary.LearningSignal,
        original: String? = null,
        replacement: String,
        previousWord: String? = null,
        nextWord: String? = null,
    ) {
        val normalizedOriginal = original?.lowercase()
        val normalizedReplacement = replacement.lowercase()
        val persistedWords = dao.all().map { it.word.lowercase() }.toSet()
        val learnable = PersonalLearningPolicy.mayLearnReplacement(
            signal, normalizedReplacement,
            isKnown = { it in baseByWord || it in persistedWords },
            isBaseWord = { it in baseByWord },
        )
        if (learnable) {
            repeat(if (signal == com.iaido.core.dictionary.LearningSignal.DELETE_RETYPE) 3 else 1) {
                reinforce(normalizedReplacement)
            }
        }
        if (signal != com.iaido.core.dictionary.LearningSignal.EXPLICIT_ADD &&
            normalizedOriginal != null && normalizedOriginal != normalizedReplacement
        ) {
            penalize(normalizedOriginal)
        }
        if (learnable && signal == com.iaido.core.dictionary.LearningSignal.DELETE_RETYPE) {
            previousWord?.let { reinforceNgram(it.lowercase(), normalizedReplacement) }
            nextWord?.let { reinforceNgram(normalizedReplacement, it.lowercase()) }
        }
    }

    private fun penalize(word: String) {
        val rows = dao.all().filter { it.word.lowercase() == word }
        val current = rows.maxByOrNull { it.boost }
        val boost = ((current?.boost ?: 1.0) * PersonalLearningPolicy.CORRECTION_DECAY)
            .coerceAtLeast(PersonalLearningPolicy.MIN_BOOST)
        if (word !in baseByWord && boost < 1.0) {
            // A custom word whose frequency would fall below the custom base is removed outright.
            forget(word)
        } else {
            rows.filter { it.word != word }.forEach { dao.forget(it.word) }
            dao.save(PersonalOverrideEntity(word, boost, current?.uses ?: 0))
        }
    }

    fun ngramBoost(previousWord: String, nextWord: String): Double =
        dao.allNgrams().firstOrNull {
            it.previousWord.lowercase() == previousWord.lowercase() &&
                it.nextWord.lowercase() == nextWord.lowercase()
        }?.boost ?: 1.0

    private fun reinforceNgram(previousWord: String, nextWord: String) {
        val current = dao.allNgrams().firstOrNull { it.previousWord == previousWord && it.nextWord == nextWord }
        val uses = current?.uses ?: 0
        val boost = ((current?.boost ?: 1.0) + 1.0 / (uses + 1)).coerceAtMost(PersonalLearningPolicy.DEFAULT_MAX_BOOST)
        dao.saveNgram(PersonalNgramOverrideEntity(previousWord, nextWord, boost, uses + 1))
    }

    fun forget(word: String) {
        val normalizedWord = word.lowercase()
        dao.all()
            .filter { it.word.lowercase() == normalizedWord }
            .forEach { dao.forget(it.word) }
        // forgetNgrams matches by exact word, so also remove differently-cased stored spellings.
        dao.allNgrams()
            .flatMap { listOf(it.previousWord, it.nextWord) }
            .filter { it.lowercase() == normalizedWord }
            .distinct()
            .forEach { dao.forgetNgrams(it) }
        dao.forgetNgrams(normalizedWord)
    }

    fun reset() {
        dao.reset()
        dao.resetNgrams()
    }
}
