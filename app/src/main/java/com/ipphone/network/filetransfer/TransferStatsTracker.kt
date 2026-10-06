package com.ipphone.network.filetransfer

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Live numbers for one transfer, kept in memory only (no database writes). */
data class TransferStats(
    val transferId: String,
    val bytesDone: Long,
    val totalBytes: Long,
    /** Smoothed current speed in bytes/second. */
    val speedBps: Long,
    /** Average speed since this run of the transfer started. */
    val avgSpeedBps: Long,
    /** Estimated seconds left, or -1 when unknown. */
    val etaSeconds: Long,
    val elapsedMs: Long,
    val finished: Boolean = false
)

@Singleton
class TransferStatsTracker @Inject constructor() {

    private class Sample(
        val startMs: Long,
        val startBytes: Long,
        var lastMs: Long,
        var lastBytes: Long,
        var ema: Double = 0.0
    )

    private val samples = ConcurrentHashMap<String, Sample>()
    private val _stats = MutableStateFlow<Map<String, TransferStats>>(emptyMap())
    val stats: StateFlow<Map<String, TransferStats>> = _stats.asStateFlow()

    fun update(transferId: String, bytesDone: Long, totalBytes: Long) {
        val now = SystemClock.elapsedRealtime()
        val s = samples.getOrPut(transferId) { Sample(now, bytesDone, now, bytesDone) }

        if (bytesDone < s.lastBytes) {          // resumed / restarted from an earlier offset
            s.lastBytes = bytesDone
            s.lastMs = now
        }

        val dt = now - s.lastMs
        if (dt >= 400) {
            val instant = (bytesDone - s.lastBytes) * 1000.0 / dt
            s.ema = if (s.ema <= 0.0) instant else s.ema * 0.7 + instant * 0.3
            s.lastMs = now
            s.lastBytes = bytesDone
        }

        val elapsed = (now - s.startMs).coerceAtLeast(1L)
        val avg = ((bytesDone - s.startBytes) * 1000L / elapsed).coerceAtLeast(0L)
        val speed = s.ema.toLong().coerceAtLeast(0L)
        val remaining = (totalBytes - bytesDone).coerceAtLeast(0L)
        val eta = if (speed > 0) remaining / speed else -1L

        publish(
            TransferStats(
                transferId  = transferId,
                bytesDone   = bytesDone,
                totalBytes  = totalBytes,
                speedBps    = speed,
                avgSpeedBps = avg,
                etaSeconds  = eta,
                elapsedMs   = elapsed
            )
        )
    }

    /** Freeze the numbers (average speed stays visible) once a transfer ends. */
    fun complete(transferId: String) {
        samples.remove(transferId)
        _stats.update { current ->
            val old = current[transferId] ?: return@update current
            current + (transferId to old.copy(
                bytesDone = old.totalBytes,
                speedBps = old.avgSpeedBps,
                etaSeconds = 0L,
                finished = true
            ))
        }
    }

    fun remove(transferId: String) {
        samples.remove(transferId)
        _stats.update { it - transferId }
    }

    private fun publish(stats: TransferStats) {
        _stats.update { it + (stats.transferId to stats) }
    }
}
