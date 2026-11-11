package de.cookdesigns.reverselab

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max

internal fun writeWaves(pcm: File, normal: File, reverse: File, rate: Int, channels: Int): FloatArray {
    val frameSize = channels * 2
    require(rate > 0 && channels in 1..8)
    val frames = pcm.length() / frameSize
    require(frames > 0 && pcm.length() % frameSize == 0L && pcm.length() <= Int.MAX_VALUE - 44)
    val peaks = FloatArray(96)
    fun RandomAccessFile.header() {
        setLength(0)
        writeBytes("RIFF")
        writeInt(Integer.reverseBytes((36 + pcm.length()).toInt()))
        writeBytes("WAVEfmt ")
        writeInt(Integer.reverseBytes(16))
        writeShort(256)
        writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
        writeInt(Integer.reverseBytes(rate))
        writeInt(Integer.reverseBytes(rate * frameSize))
        writeShort(java.lang.Short.reverseBytes(frameSize.toShort()).toInt())
        writeShort(4096)
        writeBytes("data")
        writeInt(Integer.reverseBytes(pcm.length().toInt()))
    }
    RandomAccessFile(pcm, "r").use { source ->
        RandomAccessFile(normal, "rw").use { forward ->
            RandomAccessFile(reverse, "rw").use { backward ->
                forward.header()
                backward.header()
                val block = ByteArray(4096 * frameSize)
                var end = frames
                while (end > 0) {
                    val count = minOf(end, 4096).toInt()
                    val start = end - count
                    val bytes = count * frameSize
                    source.seek(start * frameSize)
                    source.readFully(block, 0, bytes)
                    forward.seek(44 + start * frameSize)
                    forward.write(block, 0, bytes)
                    for (i in 0 until count) {
                        val bin = ((start + i) * peaks.size / frames).toInt()
                        for (channel in 0 until channels) {
                            val offset = i * frameSize + channel * 2
                            val sample = (block[offset + 1].toInt() shl 8) or (block[offset].toInt() and 255)
                            peaks[bin] = max(peaks[bin], abs(sample) / 32768f)
                        }
                    }
                    for (i in 0 until count / 2) {
                        for (b in 0 until frameSize) {
                            val a = i * frameSize + b
                            val z = (count - 1 - i) * frameSize + b
                            val temp = block[a]
                            block[a] = block[z]
                            block[z] = temp
                        }
                    }
                    backward.write(block, 0, bytes)
                    end = start
                }
            }
        }
    }
    return peaks
}
