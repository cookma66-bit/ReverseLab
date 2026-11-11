package de.cookdesigns.reverselab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WaveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun reversesStereoFramesAcrossBlockBoundary() {
        val frames = 9001
        val samples = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            samples.putShort(it.toShort())
            samples.putShort((-it).toShort())
        }
        val pcm = folder.newFile().apply { writeBytes(samples.array()) }
        val normal = folder.newFile()
        val reverse = folder.newFile()
        val peaks = writeWaves(pcm, normal, reverse, 44100, 2)
        assertArrayEquals(samples.array(), normal.readBytes().copyOfRange(44, 44 + frames * 4))
        val result = ByteBuffer.wrap(reverse.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(result.array(), 0, 4, Charsets.US_ASCII))
        assertEquals(2, result.getShort(22).toInt())
        assertEquals(44100, result.getInt(24))
        assertEquals(frames * 4, result.getInt(40))
        result.position(44)
        for (i in frames - 1 downTo 0) {
            assertEquals(i, result.short.toInt())
            assertEquals(-i, result.short.toInt())
        }
        assertEquals(96, peaks.size)
        assertTrue(peaks.last() > peaks.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialStereoFrame() {
        val pcm = folder.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        writeWaves(pcm, folder.newFile(), folder.newFile(), 44100, 2)
    }
}
