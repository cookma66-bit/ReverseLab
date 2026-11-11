package de.cookdesigns.reverselab

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

private val lime = Color(0xFFC8FB59)
private val ink = Color(0xFF101310)
private val panel = Color(0xFF1B211B)

class MainActivity : ComponentActivity() {
    private lateinit var studio: Studio
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) studio.startRecording()
        else studio.message = "Erlaube den Mikrofonzugriff in den Einstellungen, um Audio aufzunehmen."
    }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(studio::importAudio)
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        it?.let(studio::export)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        studio = ViewModelProvider(this)[Studio::class.java]
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = lime, onPrimary = ink, background = ink, surface = panel,
                onSurface = Color(0xFFEDF2E8), onSurfaceVariant = Color(0xFFB9C6B4)
            )) {
                Surface(color = ink, modifier = Modifier.fillMaxSize()) {
                    StudioScreen(studio, onRecord = {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            studio.startRecording()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, onImport = {
                        importer.launch(arrayOf("audio/*", "application/octet-stream"))
                    }, onExport = {
                        exporter.launch("ReverseLab-rueckwaerts.wav")
                    }, onShare = {
                        studio.share { file ->
                            val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/wav"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("ReverseLab WAV", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "WAV teilen"))
                        }
                    })
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && studio.recording) studio.finishRecording()
        if (!isChangingConfigurations && studio.playing) studio.togglePlayback()
        super.onStop()
    }
}

@Composable
private fun StudioScreen(studio: Studio, onRecord: () -> Unit, onImport: () -> Unit,
    onExport: () -> Unit, onShare: () -> Unit) {
    var library by remember { mutableStateOf(false) }
    val clip = studio.clip
    val ready = !studio.busy && !studio.recording
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("ReverseLab", style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black, color = lime)
            Text("Audio aufnehmen, umdrehen und teilen", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = panel)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (studio.recording) "Mikrofon" else clip?.title ?: "Audiostudio",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (studio.recording) {
                        Text(clock(studio.recordTime), style = MaterialTheme.typography.displayMedium, color = lime)
                        Text(if (studio.paused) "Aufnahme pausiert" else "Aufnahme läuft")
                        OutlinedButton(onClick = studio::pauseRecording, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (studio.paused) "Fortsetzen" else "Pausieren")
                        }
                        Button(onClick = studio::finishRecording, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Stoppen und speichern")
                        }
                    } else if (clip != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!studio.reversed, { studio.select(false) }, { Text("Original") }, enabled = ready)
                            FilterChip(studio.reversed, { studio.select(true) }, { Text("Rückwärts") }, enabled = ready)
                        }
                        val fraction = studio.position.toFloat() / studio.duration.coerceAtLeast(1)
                        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                            val step = size.width / clip.peaks.size
                            clip.peaks.indices.forEach { i ->
                                val peak = clip.peaks[if (studio.reversed) clip.peaks.lastIndex - i else i]
                                val half = (peak * size.height * 0.46f).coerceAtLeast(1.5.dp.toPx())
                                val x = (i + 0.5f) * step
                                drawLine(if (i.toFloat() / clip.peaks.size <= fraction) lime else Color(0xFF647455),
                                    Offset(x, center.y - half), Offset(x, center.y + half),
                                    strokeWidth = (step * 0.53f).coerceAtLeast(1f), cap = StrokeCap.Round)
                            }
                        }
                        Slider(value = studio.position.toFloat(), onValueChange = { studio.seek(it.toInt()) },
                            valueRange = 0f..studio.duration.coerceAtLeast(1).toFloat(), enabled = ready)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(clock(studio.position))
                            Text(clock(studio.duration))
                        }
                        Button(onClick = studio::togglePlayback, enabled = ready,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (studio.playing) "Pausieren" else "Abspielen")
                        }
                        OutlinedButton(onClick = studio::stop, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                            Text("Wiedergabe stoppen")
                        }
                    } else {
                        Text("Starte eine Aufnahme oder wähle eine Audiodatei aus.")
                    }
                }
            }
            if (studio.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (studio.message.isNotBlank()) Text(studio.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!studio.recording) {
                Button(onClick = onRecord, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Aufnahme starten")
                }
                OutlinedButton(onClick = onImport, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Audio importieren")
                }
                TextButton(onClick = { library = true }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Gespeicherte Aufnahmen")
                }
                if (clip != null) {
                    HorizontalDivider()
                    OutlinedButton(onClick = onExport, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Rückwärts-WAV speichern")
                    }
                    OutlinedButton(onClick = onShare, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Rückwärts-WAV teilen")
                    }
                }
            }
        }
    }
    if (library) {
        val recordings = remember { studio.recordings() }
        AlertDialog(onDismissRequest = { library = false }, title = { Text("Gespeicherte Aufnahmen") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (recordings.isEmpty()) Text("Es gibt noch keine gespeicherten Aufnahmen.")
                    recordings.forEach { file ->
                        TextButton(onClick = {
                            library = false
                            studio.openRecording(file)
                        }) {
                            val date = java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.GERMANY)
                            Text(date.format(java.util.Date(file.lastModified())))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { library = false }) { Text("Schließen") } })
    }
}

private fun clock(ms: Int): String {
    val seconds = ms / 1000
    return String.format(Locale.GERMANY, "%02d:%02d", seconds / 60, seconds % 60)
}
