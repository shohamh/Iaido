package com.iaido.app

import androidx.room.Room
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDatabaseInstrumentedTest {
    @Test
    fun `generated database implementation opens on device`() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalDictionaryDatabase::class.java,
        ).build()

        database.close()
    }
}
