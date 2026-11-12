package de.cookdesigns.reverselab

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class Clip(val title: String, val original: File, val reverse: File, val peaks: FloatArray,
    val recording: File?)

internal class Studio(private val app: Application) : AndroidViewModel(app) {
    var clip by mutableStateOf<Clip?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    var reversed by mutableStateOf(true)
        private set
    var playing by mutableStateOf(false)
        private set
    var speed by mutableStateOf(1f)
        private set
    var duration by mutableStateOf(0)
        private set
    var position by mutableStateOf(0)
        private set
    var recording by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var recordTime by mutableStateOf(0)
        private set
    private var player: MediaPlayer? = null
    private var appliedSpeed = 1f
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var elapsed = 0L
    private var started = 0L

    init {
        viewModelScope.launch {
            while (true) {
                delay(60)
                if (playing) position = player?.currentPosition ?: 0
                if (recording && !paused) updateRecordTime()
            }
        }
    }

    private fun updateRecordTime() {
        recordTime = (elapsed + SystemClock.elapsedRealtime() - started).toInt()
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun startRecording() {
        if (busy || recording) return
        stop()
        val folder = File(app.filesDir, "recordings").apply { mkdirs() }
        val file = File(folder, "Aufnahme-${System.currentTimeMillis()}.m4a")
        try {
            val device = MediaRecorder()
            recorder = device
            device.setAudioSource(MediaRecorder.AudioSource.MIC)
            device.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            device.setAudioSamplingRate(44100)
            device.setAudioEncodingBitRate(128000)
            device.setOutputFile(file.path)
            device.prepare()
            device.start()
            recordingFile = file
            elapsed = 0
            started = SystemClock.elapsedRealtime()
            recordTime = 0
            paused = false
            recording = true
            message = ""
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            file.delete()
            message = "Das Mikrofon konnte nicht gestartet werden."
        }
    }

    fun pauseRecording() {
        val device = recorder ?: return
        try {
            if (paused) {
                device.resume()
                started = SystemClock.elapsedRealtime()
            } else {
                device.pause()
                elapsed += SystemClock.elapsedRealtime() - started
                recordTime = elapsed.toInt()
            }
            paused = !paused
        } catch (_: Exception) {
            message = "Der Aufnahmestatus konnte nicht geändert werden."
        }
    }

    fun finishRecording() {
        val device = recorder ?: return
        val file = recordingFile ?: return
        val saved = runCatching { device.stop() }.isSuccess
        device.release()
        recorder = null
        recordingFile = null
        recording = false
        paused = false
        if (saved) prepare(file.nameWithoutExtension, "Aufnahme lokal gespeichert.") { file }
        else {
            file.delete()
            message = "Die Aufnahme konnte nicht gespeichert werden. Versuche eine längere Aufnahme."
        }
    }

    fun recordings(): List<File> = File(app.filesDir, "recordings")
        .listFiles { file -> file.isFile && file.extension == "m4a" }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun openRecording(file: File) = prepare(file.nameWithoutExtension) { file }

    fun deleteRecording(file: File): Boolean {
        if (busy || recording || file !in recordings()) return false
        val deleted = removeRecording(file)
        if (deleted) message = "Aufnahme gelöscht."
        return deleted
    }

    fun deleteAllRecordings(): Boolean {
        if (busy || recording) return false
        val failed = recordings().count { !removeRecording(it) }
        message = if (failed == 0) "Alle Aufnahmen gelöscht." else ""
        return failed == 0
    }

    private fun removeRecording(file: File): Boolean {
        if (!file.delete()) return false
        val current = clip
        if (current?.recording == file) {
            player?.release()
            player = null
            playing = false
            position = 0
            duration = 0
            clip = null
            current.original.parentFile?.deleteRecursively()
        }
        return true
    }

    fun importAudio(uri: Uri) {
        prepare("Importiertes Audio") { dir ->
            File(dir, "import").also { file ->
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun prepare(title: String, success: String = "", source: (File) -> File) {
        if (busy || recording) return
        busy = true
        message = ""
        stop()
        viewModelScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                val dir = File.createTempFile("clip-", ".tmp", app.cacheDir)
                dir.delete()
                dir.mkdir()
                val pcm = File(dir, "decoded.pcm")
                val original = File(dir, "Original.wav")
                val reverse = File(dir, "Rückwärts.wav")
                try {
                    val input = source(dir)
                    val (rate, channels) = decodeAudio(input, pcm)
                    val peaks = writeWaves(pcm, original, reverse, rate, channels)
                    if (input.parentFile == dir) input.delete()
                    Clip(title, original, reverse, peaks, input.takeUnless { it.parentFile == dir })
                } catch (failure: Exception) {
                    dir.deleteRecursively()
                    throw failure
                } finally {
                    pcm.delete()
                }
            }
            val previous = clip
            if (loadPlayer(result.reverse)) {
                clip = result
                reversed = true
                previous?.original?.parentFile?.deleteRecursively()
                message = success
            } else result.original.parentFile?.deleteRecursively()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = if (success.isEmpty()) "Audio konnte nicht gelesen werden. Prüfe Dateiformat, Datei und freien Speicher."
                    else "Aufnahme gespeichert, aber die Verarbeitung ist fehlgeschlagen."
            } finally {
                busy = false
            }
        }
    }

    private fun loadPlayer(file: File): Boolean {
        val next = MediaPlayer()
        return try {
            next.setDataSource(file.path)
            next.prepare()
            next.setOnCompletionListener { completed ->
                completed.seekTo(0)
                playing = false
                position = 0
            }
            next.setOnErrorListener { _, _, _ ->
                playing = false
                message = "Die Wiedergabe wurde unterbrochen."
                true
            }
            player?.release()
            player = next
            appliedSpeed = 1f
            playing = false
            position = 0
            duration = next.duration
            true
        } catch (_: Exception) {
            next.release()
            message = "Die Audiodatei kann nicht abgespielt werden."
            false
        }
    }

    fun select(reverse: Boolean) {
        if (reversed == reverse) return
        val current = clip ?: return
        val oldPosition = position
        if (loadPlayer(if (reverse) current.reverse else current.original)) {
            reversed = reverse
            seek((duration - oldPosition).takeIf { oldPosition > 0 } ?: 0)
        }
    }

    fun togglePlayback() = playerAction {
        if (playing) {
            pause()
            playing = false
        } else {
            if (speed == appliedSpeed || !applySpeed(this, speed)) start()
            playing = true
        }
    }

    fun selectSpeed(value: Float) {
        if (!playing) {
            speed = value
            return
        }
        playerAction {
            if (applySpeed(this, value)) speed = value
        }
    }

    private fun applySpeed(device: MediaPlayer, value: Float): Boolean = try {
        device.playbackParams = PlaybackParams().setSpeed(value).setPitch(1f)
        appliedSpeed = value
        true
    } catch (_: IllegalArgumentException) {
        speed = appliedSpeed
        message = "Diese Geschwindigkeit ist auf dem Gerät nicht verfügbar."
        false
    }

    fun stop() = playerAction {
        if (isPlaying) pause()
        playing = false
        seek(0)
    }

    fun seek(ms: Int) = playerAction {
        position = ms.coerceIn(0, duration)
        seekTo(position)
    }

    private inline fun playerAction(action: MediaPlayer.() -> Unit) {
        val current = player ?: return
        try {
            current.action()
        } catch (_: IllegalStateException) {
            playing = false
            message = "Die Wiedergabe ist gerade nicht verfügbar."
        }
    }

    fun export(uri: Uri) {
        val file = clip?.reverse ?: return
        fileOperation("WAV gespeichert.") {
            requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }
    }

    fun share(send: (File) -> Unit) {
        val file = clip?.reverse ?: return
        val folder = File(app.filesDir, "shares")
        val shared = File(folder, "ReverseLab-${System.currentTimeMillis()}.wav")
        fileOperation(after = { send(shared) }) {
            folder.mkdirs()
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
                ?.forEach { it.delete() }
            file.copyTo(shared)
        }
    }

    private fun fileOperation(success: String = "", after: () -> Unit = {}, block: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                after()
                message = success
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Die WAV-Datei konnte nicht bereitgestellt werden."
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        if (recording) {
            val saved = runCatching { recorder?.stop() }.isSuccess
            if (!saved) recordingFile?.delete()
            recorder?.release()
        }
        player?.release()
        clip?.original?.parentFile?.deleteRecursively()
    }
}
