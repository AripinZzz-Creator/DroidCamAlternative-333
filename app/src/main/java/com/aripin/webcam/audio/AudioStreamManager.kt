package com.aripin.webcam.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import com.aripin.webcam.model.AudioSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

class AudioStreamManager(
    private val onAudioSamples: (pcmBytes: ByteArray, length: Int, ptsUs: Long) -> Unit,
    private val onAudioLevelDb: (db: Float) -> Unit
) {
    companion object {
        private const val TAG = "AudioStreamManager"
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var captureThread: Thread? = null

    @Volatile
    private var currentSettings = AudioSettings()

    @SuppressLint("MissingPermission")
    fun start(settings: AudioSettings) {
        currentSettings = settings
        if (!settings.enabled) {
            stop()
            return
        }
        if (isRecording.getAndSet(true)) return

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                settings.sampleRate,
                settings.channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record = AudioRecord(
                settings.source.sourceId,
                settings.sampleRate,
                settings.channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                isRecording.set(false)
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()

            captureThread = Thread({ readAudioLoop(record, minBufSize) }, "WebcamAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(TAG, "Audio streaming started: ${settings.sampleRate}Hz, channels=${settings.channelCount}")
        } catch (e: Exception) {
            Log.e(TAG, "Audio start error: ${e.message}", e)
            isRecording.set(false)
        }
    }

    fun updateSettings(newSettings: AudioSettings) {
        val requiresRestart = newSettings.enabled != currentSettings.enabled ||
            newSettings.source != currentSettings.source ||
            newSettings.sampleRate != currentSettings.sampleRate ||
            newSettings.channelConfig != currentSettings.channelConfig

        currentSettings = newSettings

        if (requiresRestart) {
            stop()
            if (newSettings.enabled) {
                start(newSettings)
            }
        }
    }

    private fun readAudioLoop(record: AudioRecord, bufferSize: Int) {
        val chunkSize = (currentSettings.sampleRate / 50) * 2 * currentSettings.channelCount
        val buffer = ByteArray(chunkSize.coerceAtMost(bufferSize))

        while (isRecording.get()) {
            val readBytes = record.read(buffer, 0, buffer.size)
            if (readBytes > 0) {
                val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
                if (currentSettings.muted) {
                    buffer.fill(0, 0, readBytes)
                    onAudioLevelDb(-60f)
                } else {
                    onAudioLevelDb(computeRmsDb(buffer, readBytes))
                }
                onAudioSamples(buffer, readBytes, ptsUs)
            }
        }
    }

    private fun computeRmsDb(pcm16Le: ByteArray, length: Int): Float {
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toDouble() * sample.toDouble()
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return -60f
        val rms = sqrt(sumSquares / sampleCount)
        if (rms < 1.0) return -60f
        val db = 20.0 * log10(rms / 32768.0)
        return db.toFloat().coerceIn(-60f, 0f)
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        try {
            captureThread?.join(400)
        } catch (_: Exception) {
        }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        onAudioLevelDb(-60f)
    }
}
