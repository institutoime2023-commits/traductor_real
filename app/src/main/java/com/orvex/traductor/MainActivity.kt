package com.orvex.traductor

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.orvex.traductor.translation.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : ComponentActivity() {

    init {
        System.loadLibrary("whisper_jni")
    }

    private external fun transcribeAudioNative(modelPath: String, audioData: FloatArray): String
    private external fun freeWhisperNative()

    private val translationManager = TranslationManager()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainScreen(
                    onDownloadWhisper = { onStatus -> downloadWhisperModel(onStatus) },
                    onDownloadTranslation = { onStatus ->
                        translationManager.downloadModel { _, msg ->
                            onStatus(msg)
                        }
                    },
                    onRecordAndTranslate = { onProgress, onFinish ->
                        startAudioRecordingAndTranscribe(onProgress, onFinish)
                    }
                )
            }
        }
    }

    private fun downloadWhisperModel(onStatus: (String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onStatus("Descargando modelo Whisper...") }
                val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
                val file = File(filesDir, "ggml-tiny.en.bin")

                if (file.exists() && file.length() > 1024 * 1024) {
                    withContext(Dispatchers.Main) { onStatus("Modelo Whisper ya existe (${file.length() / (1024 * 1024)} MB).") }
                    return@launch
                }

                URL(modelUrl).openStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) { onStatus("Modelo Whisper descargado con éxito.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Error al descargar Whisper: ${e.message}") }
            }
        }
    }

    private fun startAudioRecordingAndTranscribe(
        onProgress: (String) -> Unit,
        onFinish: (String, String, String) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val modelFile = File(filesDir, "ggml-tiny.en.bin")
            if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
                withContext(Dispatchers.Main) {
                    onFinish(
                        "Modelo Whisper no encontrado.",
                        "Por favor descarga primero el modelo Whisper.",
                        "Error: Falta modelo"
                    )
                }
                return@launch
            }

            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                withContext(Dispatchers.Main) {
                    onFinish(
                        "Permiso de micrófono denegado.",
                        "Concede el permiso en los ajustes de la aplicación.",
                        "Sin permiso"
                    )
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                onProgress("Grabando audio ambiental (6 segundos)...")
            }

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBuffer, sampleRate * 2)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    onFinish("Error al inicializar micrófono.", "", "Error de hardware")
                }
                return@launch
            }

            val totalSamples = sampleRate * 6
            val shortBuffer = ShortArray(minBuffer / 2)
            val audioSamples = FloatArray(totalSamples)
            var recordedCount = 0

            try {
                audioRecord.startRecording()
                while (recordedCount < totalSamples) {
                    val toRead = minOf(shortBuffer.size, totalSamples - recordedCount)
                    val read = audioRecord.read(shortBuffer, 0, toRead)
                    if (read > 0) {
                        for (i in 0 until read) {
                            audioSamples[recordedCount + i] = shortBuffer[i] / 32768.0f
                        }
                        recordedCount += read
                    } else {
                        break
                    }
                }
            } finally {
                try {
                    audioRecord.stop()
                } catch (_: Exception) {}
                audioRecord.release()
            }

            withContext(Dispatchers.Main) {
                onProgress("Reconociendo voz con Whisper.cpp...")
            }

            val rawRecognized = transcribeAudioNative(modelFile.absolutePath, audioSamples).trim()

            if (rawRecognized.isBlank() || rawRecognized == "[BLANK_AUDIO]") {
                withContext(Dispatchers.Main) {
                    onFinish(
                        "(No se detectó voz clara en inglés)",
                        "(Sin texto para traducir)",
                        "Audio sin voz detectada"
                    )
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                onProgress("Traduciendo al español con ML Kit...")
            }

            translationManager.translateText(rawRecognized) { translated ->
                coroutineScope.launch(Dispatchers.Main) {
                    onFinish(rawRecognized, translated, "Completado con éxito")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        freeWhisperNative()
        translationManager.close()
    }
}

@Composable
fun MainScreen(
    onDownloadWhisper: ((String) -> Unit) -> Unit,
    onDownloadTranslation: ((String) -> Unit) -> Unit,
    onRecordAndTranslate: ((String) -> Unit, (String, String, String) -> Unit) -> Unit
) {
    var whisperStatus by remember { mutableStateOf("Comprobar estado") }
    var translationStatus by remember { mutableStateOf("Pendiente") }
    var recognizedText by remember { mutableStateOf("Aquí aparecerá el texto reconocido por Whisper.") }
    var translatedText by remember { mutableStateOf("Aquí aparecerá el texto traducido por ML Kit.") }
    var processingState by remember { mutableStateOf("Esperando...") }
    var isProcessing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "TRADUCTOR V0.3 ANDROID", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "MODELO WHISPER", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = !isProcessing,
                    onClick = { onDownloadWhisper { statusMsg -> whisperStatus = statusMsg } }
                ) {
                    Text("DESCARGAR WHISPER")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Estado: $whisperStatus", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "MODELO DE TRADUCCIÓN", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = !isProcessing,
                    onClick = { onDownloadTranslation { statusMsg -> translationStatus = statusMsg } }
                ) {
                    Text("DESCARGAR TRADUCCIÓN")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Estado: $translationStatus", style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing,
            onClick = {
                isProcessing = true
                processingState = "Iniciando captura..."
                onRecordAndTranslate(
                    { progressMsg ->
                        processingState = progressMsg
                    },
                    { english, spanish, finalState ->
                        recognizedText = english
                        translatedText = spanish
                        processingState = finalState
                        isProcessing = false
                    }
                )
            }
        ) {
            Text(if (isProcessing) "GRABANDO Y PROCESANDO..." else "GRABAR · 6 SEGUNDOS Y TRADUCIR")
        }

        Text(text = "Estado: $processingState", style = MaterialTheme.typography.bodyMedium)

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "TEXTO ORIGINAL (INGLÉS)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = recognizedText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "TRADUCCIÓN (ESPAÑOL)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = translatedText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
