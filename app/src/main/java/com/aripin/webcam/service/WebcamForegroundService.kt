package com.aripin.webcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Range
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aripin.webcam.audio.AudioStreamManager
import com.aripin.webcam.camera.CameraStreamManager
import com.aripin.webcam.model.AudioSettings
import com.aripin.webcam.model.CameraSettings
import com.aripin.webcam.model.ServerConfig
import com.aripin.webcam.model.StreamStats
import com.aripin.webcam.model.VideoSettings
import com.aripin.webcam.network.NetworkUtils
import com.aripin.webcam.network.StreamServerManager
import com.aripin.webcam.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class WebcamForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "droidcam_alt_stream_channel"
        private const val NOTIFICATION_ID = 4747
    }

    inner class LocalBinder : Binder() {
        fun getService(): WebcamForegroundService = this@WebcamForegroundService
    }

    private val binder = LocalBinder()

    private val _statsFlow = MutableStateFlow(StreamStats())
    val statsFlow: StateFlow<StreamStats> = _statsFlow.asStateFlow()

    private val _videoSettingsFlow = MutableStateFlow(VideoSettings())
    val videoSettingsFlow: StateFlow<VideoSettings> = _videoSettingsFlow.asStateFlow()

    private val _cameraSettingsFlow = MutableStateFlow(CameraSettings())
    val cameraSettingsFlow: StateFlow<CameraSettings> = _cameraSettingsFlow.asStateFlow()

    private val _audioSettingsFlow = MutableStateFlow(AudioSettings())
    val audioSettingsFlow: StateFlow<AudioSettings> = _audioSettingsFlow.asStateFlow()

    private val _serverConfigFlow = MutableStateFlow(ServerConfig())
    val serverConfigFlow: StateFlow<ServerConfig> = _serverConfigFlow.asStateFlow()

    var hasFlashUnit: Boolean = false
        private set
    var minFocusDistance: Float = 0f
        private set
    var exposureRange: Range<Int> = Range(-12, 12)
        private set
    var maxZoomRatio: Float = 4.0f
        private set

    private lateinit var serverManager: StreamServerManager
    private lateinit var cameraManager: CameraStreamManager
    private lateinit var audioManager: AudioStreamManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var telemetryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        serverManager = StreamServerManager(
            onRemoteCommand = { cmd -> handleRemoteCommand(cmd) },
            onClientsChanged = { ips ->
                _statsFlow.update {
                    it.copy(
                        connectedClients = ips.size,
                        clientAddresses = ips,
                        statusMessage = if (ips.isNotEmpty()) "Streaming to ${ips.joinToString()}" else "Waiting for PC client..."
                    )
                }
            }
        )

        cameraManager = CameraStreamManager(
            context = this,
            lifecycleOwner = this,
            onH264Frame = { data, ptsUs, isConfig ->
                serverManager.broadcastH264Unit(data, ptsUs, isConfig)
            },
            onMjpegFrame = { jpegData, ptsUs ->
                serverManager.broadcastMjpegFrame(jpegData, ptsUs)
            },
            onHardwareCapabilitiesDetected = { flash, minFocus, expRange, maxZoom ->
                hasFlashUnit = flash
                minFocusDistance = minFocus
                exposureRange = expRange
                maxZoomRatio = maxZoom
            }
        )

        audioManager = AudioStreamManager(
            onAudioSamples = { pcmBytes, len, ptsUs ->
                serverManager.broadcastAudioPcm(pcmBytes, len, ptsUs)
            },
            onAudioLevelDb = { db ->
                _statsFlow.update { it.copy(audioLevelDb = db) }
            }
        )

        refreshNetworkEndpoints()
        cameraManager.startCamera(_videoSettingsFlow.value, _cameraSettingsFlow.value, streaming = false)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun attachPreviewView(previewView: PreviewView?) {
        cameraManager.attachPreviewView(previewView)
    }

    fun triggerTapToFocus(x: Float, y: Float) {
        cameraManager.triggerTapToFocus(x, y)
    }

    fun startStreaming() {
        if (_statsFlow.value.isStreaming) return

        acquireLocks()
        val notification = buildNotification("Webcam Server Active - Ready for OBS / Zoom / Meet")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val cfg = _serverConfigFlow.value
        val vid = _videoSettingsFlow.value
        val aud = _audioSettingsFlow.value

        serverManager.start(cfg.httpPort, cfg.tcpStreamPort)
        serverManager.updateStreamMetadata(
            width = vid.resolution.width,
            height = vid.resolution.height,
            fps = vid.fps,
            codec = vid.codecType.name,
            sampleRate = aud.sampleRate,
            channels = aud.channelCount
        )

        cameraManager.updateVideoSettings(vid, streaming = true)
        audioManager.start(aud)

        _statsFlow.update {
            it.copy(
                isStreaming = true,
                statusMessage = "Listening on ports ${cfg.httpPort} / ${cfg.tcpStreamPort}"
            )
        }
        startTelemetryLoop()
    }

    fun stopStreaming() {
        if (!_statsFlow.value.isStreaming) return

        telemetryJob?.cancel()
        audioManager.stop()
        cameraManager.updateVideoSettings(_videoSettingsFlow.value, streaming = false)
        serverManager.stop()
        releaseLocks()

        stopForeground(STOP_FOREGROUND_REMOVE)
        _statsFlow.update {
            it.copy(
                isStreaming = false,
                connectedClients = 0,
                clientAddresses = emptyList(),
                currentFps = 0f,
                bitrateKbps = 0,
                statusMessage = "Ready"
            )
        }
    }

    fun updateVideoSettings(newSettings: VideoSettings) {
        _videoSettingsFlow.value = newSettings
        cameraManager.updateVideoSettings(newSettings, _statsFlow.value.isStreaming)
        val aud = _audioSettingsFlow.value
        serverManager.updateStreamMetadata(
            width = newSettings.resolution.width,
            height = newSettings.resolution.height,
            fps = newSettings.fps,
            codec = newSettings.codecType.name,
            sampleRate = aud.sampleRate,
            channels = aud.channelCount
        )
    }

    fun updateCameraSettings(newSettings: CameraSettings) {
        _cameraSettingsFlow.value = newSettings
        cameraManager.updateCameraSettings(newSettings)
    }

    fun updateAudioSettings(newSettings: AudioSettings) {
        _audioSettingsFlow.value = newSettings
        if (_statsFlow.value.isStreaming) {
            audioManager.updateSettings(newSettings)
        }
    }

    fun updateServerConfig(newConfig: ServerConfig) {
        _serverConfigFlow.value = newConfig
        refreshNetworkEndpoints()
    }

    fun refreshNetworkEndpoints() {
        val endpoints = NetworkUtils.getNetworkEndpoints(this)
        _statsFlow.update {
            it.copy(
                wifiIpAddress = endpoints.wifiIp,
                usbIpAddress = endpoints.usbTetherIp,
                isUsbConnected = endpoints.isUsbPlugged
            )
        }
    }

    private fun handleRemoteCommand(cmd: JSONObject) {
        when (cmd.optString("action")) {
            "switch_camera" -> {
                val cur = _cameraSettingsFlow.value
                updateCameraSettings(cur.copy(useFrontCamera = !cur.useFrontCamera, torchEnabled = false))
            }
            "toggle_torch" -> {
                val cur = _cameraSettingsFlow.value
                updateCameraSettings(cur.copy(torchEnabled = !cur.torchEnabled))
            }
            "toggle_mute" -> {
                val cur = _audioSettingsFlow.value
                updateAudioSettings(cur.copy(muted = !cur.muted))
            }
            "set_exposure" -> {
                val value = cmd.optInt("value", 0)
                val cur = _cameraSettingsFlow.value
                updateCameraSettings(cur.copy(exposureCompensationIndex = value))
            }
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = lifecycleScope.launch {
            while (isActive && _statsFlow.value.isStreaming) {
                delay(1000L)
                val (bytes, frames) = serverManager.consumeStatsSnapshot()
                val endpoints = NetworkUtils.getNetworkEndpoints(this@WebcamForegroundService)
                _statsFlow.update {
                    it.copy(
                        bitrateKbps = ((bytes * 8L) / 1000L).toInt(),
                        currentFps = frames.toFloat(),
                        wifiIpAddress = endpoints.wifiIp,
                        usbIpAddress = endpoints.usbTetherIp,
                        isUsbConnected = endpoints.isUsbPlugged
                    )
                }
            }
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidCamAlt::StreamWakeLock").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidCamAlt::WifiLock").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DroidCam Alternative Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps camera and microphone webcam streaming active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidCam Alternative")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopStreaming()
        cameraManager.release()
        super.onDestroy()
    }
}
