package de.cookdesigns.reverselab

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

internal fun decodeAudio(input: File, pcm: File): Pair<Int, Int> {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(input.path)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Keine Audiospur gefunden")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        if (mime == "audio/raw") {
            val bytes = java.nio.ByteBuffer.allocate(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            pcm.outputStream().buffered().use { out ->
                while (true) {
                    bytes.clear()
                    val length = extractor.readSampleData(bytes, 0)
                    if (length < 0) break
                    bytes.position(0)
                    bytes.limit(length)
                    out.write(asPcm16(bytes, encoding))
                    extractor.advance()
                }
            }
            return rate to channels
        }
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sentEnd = false
            var receivedEnd = false
            var lastOutput = SystemClock.elapsedRealtime()
            pcm.outputStream().buffered().use { out ->
                while (!receivedEnd) {
                    if (!sentEnd) {
                        val slot = codec.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(slot))
                            buffer.clear()
                            val length = extractor.readSampleData(buffer, 0)
                            sentEnd = length < 0
                            codec.queueInputBuffer(slot, 0, maxOf(0, length),
                                maxOf(0L, extractor.sampleTime),
                                if (sentEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!sentEnd) extractor.advance()
                        }
                    }
                    val slot = codec.dequeueOutputBuffer(info, 10_000)
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        val newRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(pcm.length() == 0L || (rate == newRate && channels == newChannels))
                        rate = newRate
                        channels = newChannels
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (slot >= 0) {
                        lastOutput = SystemClock.elapsedRealtime()
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = requireNotNull(codec.getOutputBuffer(slot)).order(ByteOrder.LITTLE_ENDIAN)
                            data.limit(info.offset + info.size)
                            data.position(info.offset)
                            out.write(asPcm16(data, encoding))
                        }
                        receivedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(slot, false)
                    }
                    check(SystemClock.elapsedRealtime() - lastOutput < 15_000) { "Der Audiodecoder antwortet nicht." }
                }
            }
            return rate to channels
        } finally {
            codec.release()
        }
    } finally {
        extractor.release()
    }
}

private fun asPcm16(data: java.nio.ByteBuffer, encoding: Int): ByteArray {
    if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
        return ByteArray(data.remaining()).also { data.get(it) }
    }
    val samples = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> data.remaining()
        AudioFormat.ENCODING_PCM_FLOAT, 22 -> data.remaining() / 4
        21 -> data.remaining() / 3
        else -> error("PCM-Format wird nicht unterstützt")
    }
    return ByteArray(samples * 2).also { bytes ->
        repeat(samples) { i ->
            val sample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((data.get().toInt() and 255) - 128) shl 8
                AudioFormat.ENCODING_PCM_FLOAT -> (data.float.coerceIn(-1f, 1f) * 32767).toInt()
                21 -> { data.get(); data.short.toInt() }
                else -> data.int shr 16
            }
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
    }
}
