package com.ipphone.presentation.screens.chat

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

data class PlaybackState(
    val isPlaying: Boolean  = false,
    val messageId: String   = "",   // which message is playing
    val positionMs: Int     = 0,
    val durationMs: Int     = 0,
    val progress: Float     = 0f    // 0f–1f
)

class VoiceNotePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun play(messageId: String, uri: Uri) {
        if (_state.value.isPlaying && _state.value.messageId == messageId) {
            pause()
            return
        }
        // Stop any current playback first
        stop()

        try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                start()
                setOnCompletionListener { onCompleted() }
            }
            val dur = player!!.duration
            _state.value = PlaybackState(
                isPlaying  = true,
                messageId  = messageId,
                positionMs = 0,
                durationMs = dur,
                progress   = 0f
            )
            startProgressTracking(dur)
            Timber.d("Playing voice note: $messageId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to play voice note")
            stop()
        }
    }

    fun pause() {
        player?.pause()
        progressJob?.cancel()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun resume() {
        player?.start()
        val dur = _state.value.durationMs
        startProgressTracking(dur)
        _state.value = _state.value.copy(isPlaying = true)
    }

    fun seekTo(fraction: Float) {
        val pos = ((_state.value.durationMs) * fraction).toInt()
        player?.seekTo(pos)
        _state.value = _state.value.copy(positionMs = pos, progress = fraction)
    }

    fun stop() {
        progressJob?.cancel()
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
        _state.value = PlaybackState()
    }

    private fun onCompleted() {
        progressJob?.cancel()
        _state.value = _state.value.copy(
            isPlaying  = false,
            positionMs = _state.value.durationMs,
            progress   = 1f
        )
    }

    private fun startProgressTracking(durationMs: Int) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && player?.isPlaying == true) {
                val pos = player?.currentPosition ?: 0
                val prg = if (durationMs > 0) pos.toFloat() / durationMs else 0f
                _state.value = _state.value.copy(
                    positionMs = pos,
                    progress   = prg
                )
                delay(100)
            }
        }
    }
}
