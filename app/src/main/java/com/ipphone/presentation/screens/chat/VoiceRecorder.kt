package com.ipphone.presentation.screens.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File

data class RecordingState(
    val isRecording: Boolean = false,
    val durationMs: Long     = 0L,
    val amplitudeLevel: Float = 0f   // 0f–1f, for waveform visualisation
)

class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File?        = null

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var startTimeMs = 0L
    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts recording. Returns the output [File] path (not yet complete). */
    fun startRecording(): File? {
        return try {
            val dir  = File(context.cacheDir, "voice_notes").also { it.mkdirs() }
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            startTimeMs = System.currentTimeMillis()
            _state.value = RecordingState(isRecording = true)
            startAmplitudePolling()
            Timber.d("Recording started: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            null
        }
    }

    /** Stops recording and returns the completed [File], or null on error. */
    fun stopRecording(): File? {
        return try {
            amplitudeJob?.cancel()
            recorder?.apply { stop(); release() }
            recorder = null
            _state.value = RecordingState(isRecording = false)
            Timber.d("Recording stopped")
            outputFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop recording")
            null
        }
    }

    /** Cancels the recording without saving. */
    fun cancelRecording() {
        try {
            amplitudeJob?.cancel()
            recorder?.apply { stop(); release() }
            recorder = null
            outputFile?.delete()
            outputFile = null
            _state.value = RecordingState(isRecording = false)
            Timber.d("Recording cancelled")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel recording")
        }
    }

    private fun startAmplitudePolling() {
        amplitudeJob = scope.launch {
            while (_state.value.isRecording) {
                val amp = try {
                    (recorder?.maxAmplitude ?: 0) / 32_767f
                } catch (e: Exception) { 0f }
                val duration = System.currentTimeMillis() - startTimeMs
                _state.value = _state.value.copy(
                    durationMs     = duration,
                    amplitudeLevel = amp.coerceIn(0f, 1f)
                )
                delay(100)
            }
        }
    }
}
