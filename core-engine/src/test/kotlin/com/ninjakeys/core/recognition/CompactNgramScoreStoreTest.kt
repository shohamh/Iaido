package com.ninjakeys.core.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompactNgramScoreStoreTest {
    @Test
    fun `reads indexed delta encoded bigrams and trigrams`() {
        val bytes = ByteBuffer.allocate(36 + 16 + 20 + 3 + 3).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("NKG1".toByteArray())
            put(1)
            put(0)
            putShort(0)
            putInt(4)
            putInt(1)
            putInt(1)
            putInt(3)
            putInt(3)
            putDouble(10.0)
            putInt(0)
            putInt(0)
            putInt(3)
            putShort(1)
            putShort(0)
            putInt(0)
            putInt(1)
            putInt(0)
            putInt(3)
            putShort(1)
            putShort(0)
            put(1)
            putShort(0xFFFF.toShort())
            put(2)
            putShort(0x8000.toShort())
        }.array()

        val store = CompactNgramScoreStore.fromBytes(
            bytes,
            mapOf("hello" to 0, "world" to 1, "there" to 2),
        )

        assertEquals(10.0, store.bigram("hello", "world"), 0.0001)
        assertEquals(10.0 * 0x8000 / 0xFFFF, store.trigram("hello", "world", "there"), 0.0001)
        assertEquals(0.0, store.bigram("world", "hello"))
        assertEquals(0.0, store.bigram("unknown", "world"))
    }

    @Test
    fun `rejects unsupported asset version`() {
        val bytes = ByteArray(36).also {
            it[0] = 'N'.code.toByte()
            it[1] = 'K'.code.toByte()
            it[2] = 'G'.code.toByte()
            it[3] = '1'.code.toByte()
            it[4] = 2
        }

        assertThrows(IllegalArgumentException::class.java) {
            CompactNgramScoreStore.fromBytes(bytes, emptyMap())
        }
    }
}
