package com.aripin.webcam.model

import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.MediaRecorder

enum class ConnectionMode(val label: String) {
    WIFI("Wi-Fi"),
    USB("USB (ADB)"),
    DUAL("Wi-Fi + USB")
}

enum class VideoCodecType(val label: String) {
    H264_HARDWARE("H.264 Hardware (Low Latency)"),
    MJPEG_COMPAT("MJPEG (Universal / OBS Browser)")
}

enum class OrientationMode(val label: String) {
    AUTO("Auto Rotate"),
    PORTRAIT("Portrait (9:16)"),
    LANDSCAPE("Landscape (16:9)")
}

enum class WhiteBalanceOption(val label: String, val awbMode: Int) {
    AUTO("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TUNGSTEN("Tungsten", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    SHADE("Shade", CaptureRequest.CONTROL_AWB_MODE_SHADE)
}

enum class AudioSourceOption(val label: String, val sourceId: Int) {
    MIC("Default Microphone", MediaRecorder.AudioSource.MIC),
    CAMCORDER("Camcorder (Directional)", MediaRecorder.AudioSource.CAMCORDER),
    VOICE_COMM("Voice Call (Noise Suppressed)", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
}

data class ResolutionOption(
    val label: String,
    val width: Int,
    val height: Int
) {
    companion object {
        val PRESETS = listOf(
            ResolutionOption("480p (SD)", 640, 480),
            ResolutionOption("720p (HD)", 1280, 720),
            ResolutionOption("1080p (Full HD)", 1920, 1080),
            ResolutionOption("4K (UHD)", 3840, 2160)
        )
    }
}

data class VideoSettings(
    val resolution: ResolutionOption = ResolutionOption.PRESETS[1],
    val fps: Int = 30,
    val bitrateBps: Int = 4_000_000,
    val codecType: VideoCodecType = VideoCodecType.H264_HARDWARE,
    val jpegQuality: Int = 82,
    val orientationMode: OrientationMode = OrientationMode.AUTO
)

data class CameraSettings(
    val useFrontCamera: Boolean = false,
    val torchEnabled: Boolean = false,
    val autoFocus: Boolean = true,
    val manualFocusNormalized: Float = 0.0f,
    val exposureCompensationIndex: Int = 0,
    val whiteBalance: WhiteBalanceOption = WhiteBalanceOption.AUTO,
    val zoomRatio: Float = 1.0f
)

data class AudioSettings(
    val enabled: Boolean = true,
    val muted: Boolean = false,
    val source: AudioSourceOption = AudioSourceOption.MIC,
    val sampleRate: Int = 48000,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
) {
    val channelCount: Int
        get() = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
}

data class ServerConfig(
    val httpPort: Int = 4747,
    val tcpStreamPort: Int = 4748,
    val connectionMode: ConnectionMode = ConnectionMode.DUAL
)

data class StreamStats(
    val isStreaming: Boolean = false,
    val connectedClients: Int = 0,
    val clientAddresses: List<String> = emptyList(),
    val currentFps: Float = 0f,
    val bitrateKbps: Int = 0,
    val wifiIpAddress: String = "0.0.0.0",
    val usbIpAddress: String? = null,
    val isUsbConnected: Boolean = false,
    val audioLevelDb: Float = -60f,
    val actualWidth: Int = 1280,
    val actualHeight: Int = 720,
    val statusMessage: String = "Ready"
)
