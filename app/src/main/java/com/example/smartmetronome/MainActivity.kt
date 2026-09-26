package com.example.smartmetronome

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val sampleRate = 44100
    private var isPlaying = false
    private var isListening = false
    private var scheduler: ScheduledExecutorService? = null
    private var clickTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bpmState = mutableIntStateOf(120)
    private val isRunningState = mutableStateOf(false)
    private val isListeningState = mutableStateOf(false)
    private val beatFlashState = mutableStateOf(false)
    private val statusMessage = mutableStateOf("Bereit")
    private val thresholdState = mutableFloatStateOf(8000f) // Schwellenwert 2.000 bis 25.000

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            statusMessage.value = "Mikrofon-Berechtigung verweigert"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAudioClick()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MetronomeScreen(
                        bpm = bpmState.intValue,
                        isRunning = isRunningState.value,
                        isListening = isListeningState.value,
                        isFlashing = beatFlashState.value,
                        threshold = thresholdState.floatValue,
                        status = statusMessage.value,
                        onBpmChange = { updateBpm(it) },
                        onThresholdChange = { thresholdState.floatValue = it },
                        onStartStop = { togglePlayback() },
                        onMicSync = { startMicDetection() }
                    )
                }
            }
        }
    }

    private fun initAudioClick() {
        val clickLength = (sampleRate * 0.015).toInt()
        val pcm = ShortArray(clickLength)
        for (i in 0 until clickLength) {
            val factor = 1.0 - (i.toDouble() / clickLength)
            pcm[i] = (sin(2.0 * Math.PI * i * 2500 / sampleRate) * Short.MAX_VALUE * factor).toInt().toShort()
        }
        clickTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(clickLength * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        clickTrack?.write(pcm, 0, clickLength)
    }

    private fun updateBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(30, 300)
        bpmState.intValue = clamped
        if (isPlaying) {
            startLoop(clamped)
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            stopLoop()
        } else {
            startLoop(bpmState.intValue)
        }
    }

    private fun startLoop(bpm: Int) {
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        val intervalMs = (60000.0 / bpm).toLong()

        isPlaying = true
        isRunningState.value = true
        statusMessage.value = "Metronom läuft ($bpm BPM)"

        scheduler?.scheduleAtFixedRate({
            clickTrack?.stop()
            clickTrack?.reloadStaticData()
            clickTrack?.play()

            mainHandler.post {
                beatFlashState.value = true
                mainHandler.postDelayed({ beatFlashState.value = false }, 55)
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun stopLoop() {
        isPlaying = false
        isRunningState.value = false
        scheduler?.shutdownNow()
        statusMessage.value = "Gestoppt"
    }

    @SuppressLint("MissingPermission")
    private fun startMicDetection() {
        if (isListening) return
        stopLoop()

        isListening = true
        isListeningState.value = true
        statusMessage.value = "Höre zu: Spiele 4 Takte am Klavier..."

        Thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val buffer = ShortArray(bufferSize)
            val beatTimestamps = mutableListOf<Long>()
            var lastPeak = 0L
            val currentThreshold = thresholdState.floatValue.toInt()

            recorder.startRecording()

            while (isListening && beatTimestamps.size < 4) {
                val read = recorder.read(buffer, 0, buffer.size)
                val now = System.currentTimeMillis()
                var peak = 0
                for (i in 0 until read) {
                    peak = max(peak, abs(buffer[i].toInt()))
                }

                // Mindestens 190 ms Abstand zwischen Schlägen (Filter gegen Doppel-Trigger bis >300 BPM)
                if (peak > currentThreshold && (now - lastPeak) > 190) {
                    lastPeak = now
                    beatTimestamps.add(now)

                    mainHandler.post {
                        beatFlashState.value = true
                        mainHandler.postDelayed({ beatFlashState.value = false }, 70)
                        statusMessage.value = "Anschlag erkannt: ${beatTimestamps.size}/4"
                    }
                }
            }

            recorder.stop()
            recorder.release()
            isListening = false
            mainHandler.post { isListeningState.value = false }

            if (beatTimestamps.size == 4) {
                val diff1 = beatTimestamps[1] - beatTimestamps[0]
                val diff2 = beatTimestamps[2] - beatTimestamps[1]
                val diff3 = beatTimestamps[3] - beatTimestamps[2]
                val avgInterval = (diff1 + diff2 + diff3) / 3.0
                val detectedBpm = (60000.0 / avgInterval).roundToInt().coerceIn(30, 300)

                mainHandler.post {
                    bpmState.intValue = detectedBpm
                    startLoop(detectedBpm)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLoop()
        clickTrack?.release()
    }
}

@Composable
fun MetronomeScreen(
    bpm: Int,
    isRunning: Boolean,
    isListening: Boolean,
    isFlashing: Boolean,
    threshold: Float,
    status: String,
    onBpmChange: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onStartStop: () -> Unit,
    onMicSync: () -> Unit
) {
    val indicatorColor by animateColorAsState(
        targetValue = when {
            isFlashing -> Color(0xFF00E676)
            isListening -> Color(0xFF1E88E5)
            else -> Color(0xFF232326)
        },
        animationSpec = tween(durationMillis = 50),
        label = "indicator"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "SMART METRONOM",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 2.sp
        )

        // Anzeige mit links -5, -1 und rechts +1, +5
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StepButton(label = "-5") { onBpmChange(bpm - 5) }
                StepButton(label = "-1") { onBpmChange(bpm - 1) }
            }

            Box(
                modifier = Modifier
                    .size(170.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$bpm",
                        fontSize = 58.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "BPM",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StepButton(label = "+1") { onBpmChange(bpm + 1) }
                StepButton(label = "+5") { onBpmChange(bpm + 5) }
            }
        }

        // BPM-Schieberegler (30 bis 300)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("30", fontSize = 12.sp, color = Color.Gray)
                Text("Tempo (BPM)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("300", fontSize = 12.sp, color = Color.Gray)
            }
            Slider(
                value = bpm.toFloat(),
                onValueChange = { onBpmChange(it.toInt()) },
                valueRange = 30f..300f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Schwellenwert-Regler für Anschlags-Empfindlichkeit
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Leise (hoch)", fontSize = 12.sp, color = Color.Gray)
                Text("Mic-Empfindlichkeit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Laut (niedrig)", fontSize = 12.sp, color = Color.Gray)
            }
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 2000f..25000f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Statusmeldung
        Text(
            text = status,
            fontSize = 14.sp,
            color = Color(0xFF81D4FA),
            fontWeight = FontWeight.Medium
        )

        // Haupt-Bedienung
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStartStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFE53935) else Color(0xFF00E676)
                )
            ) {
                Text(
                    text = if (isRunning) "STOPP" else "START",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            Button(
                onClick = onMicSync,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) Color(0xFFFFA000) else Color(0xFF1E88E5)
                )
            ) {
                Text(
                    text = if (isListening) "HÖRE ZU (4 SCHLÄGE)..." else "MIC-SYNC (KLAVIER)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun StepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color(0xFF2C2C2E)
        )
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            color = Color.White
        )
    }
}
