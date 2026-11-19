package com.aripin.webcam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoHardwareEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val onEncodedNal: (data: ByteArray, ptsUs: Long, isConfig: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "VideoHardwareEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var drainThread: Thread? = null

    fun start(): Surface? {
        if (isRunning.getAndSet(true)) return inputSurface

        return try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            mediaCodec = codec
            inputSurface = surface

            drainThread = Thread({ drainEncoderLoop(codec) }, "H264DrainThread").apply { start() }
            Log.i(TAG, "Hardware H.264 encoder started: ${width}x${height} @ ${fps}fps, ${bitrateBps / 1000}kbps")
            surface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec H.264: ${e.message}", e)
            isRunning.set(false)
            null
        }
    }

    fun updateBitrate(newBitrateBps: Int) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic bitrate update failed: ${e.message}")
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (_: Exception) {
        }
    }

    private fun drainEncoderLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outputIndex >= 0) {
                    val encodedBuffer = codec.getOutputBuffer(outputIndex)
                    if (encodedBuffer != null && bufferInfo.size > 0) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val data = ByteArray(bufferInfo.size)
                        encodedBuffer.get(data)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        onEncodedNal(data, bufferInfo.presentationTimeUs, isConfig)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Encoder drain error: ${e.message}")
                }
                break
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            drainThread?.join(500)
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null
        inputSurface = null
    }
}
