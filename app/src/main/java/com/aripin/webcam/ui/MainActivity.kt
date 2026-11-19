package com.aripin.webcam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aripin.webcam.databinding.ActivityMainBinding
import com.aripin.webcam.model.AudioSourceOption
import com.aripin.webcam.model.ConnectionMode
import com.aripin.webcam.model.OrientationMode
import com.aripin.webcam.model.ResolutionOption
import com.aripin.webcam.model.VideoCodecType
import com.aripin.webcam.model.WhiteBalanceOption
import com.aripin.webcam.service.WebcamForegroundService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var webcamService: WebcamForegroundService? = null
    private var isBound = false

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] ?: false
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            startAndBindWebcamService()
        } else {
            Toast.makeText(this, "Camera & Microphone permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as WebcamForegroundService.LocalBinder
            webcamService = localBinder.getService()
            isBound = true
            webcamService?.attachPreviewView(binding.previewView)
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            webcamService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinnersAndControls()
        setupPreviewGestures()

        if (hasAllPermissions()) {
            startAndBindWebcamService()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAndBindWebcamService() {
        val intent = Intent(this, WebcamForegroundService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val svc = webcamService ?: return false
                    val cur = svc.cameraSettingsFlow.value
                    val nextZoom = (cur.zoomRatio * detector.scaleFactor).coerceIn(1.0f, svc.maxZoomRatio)
                    svc.updateCameraSettings(cur.copy(zoomRatio = nextZoom))
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                webcamService?.triggerTapToFocus(event.x, event.y)
                Toast.makeText(this, "AF/AE Locked on point", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun setupSpinnersAndControls() {
        binding.btnStartStop.setOnClickListener {
            val svc = webcamService ?: return@setOnClickListener
            if (svc.statsFlow.value.isStreaming) {
                svc.stopStreaming()
            } else {
                svc.startStreaming()
            }
        }

        binding.cardNetworkInfo.setOnClickListener {
            val text = binding.tvIpEndpoint.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Webcam Endpoint", text))
            Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
        }

        binding.btnSwitchCamera.setOnClickListener {
            val svc = webcamService ?: return@setOnClickListener
            val cur = svc.cameraSettingsFlow.value
            svc.updateCameraSettings(cur.copy(useFrontCamera = !cur.useFrontCamera, torchEnabled = false))
        }

        binding.btnTorch.setOnClickListener {
            val svc = webcamService ?: return@setOnClickListener
            if (!svc.hasFlashUnit) {
                Toast.makeText(this, "Torch unavailable on active camera", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cur = svc.cameraSettingsFlow.value
            svc.updateCameraSettings(cur.copy(torchEnabled = !cur.torchEnabled))
        }

        binding.btnOrientation.setOnClickListener {
            val svc = webcamService ?: return@setOnClickListener
            val cur = svc.videoSettingsFlow.value
            val nextMode = when (cur.orientationMode) {
                OrientationMode.AUTO -> OrientationMode.PORTRAIT
                OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                OrientationMode.LANDSCAPE -> OrientationMode.AUTO
            }
            svc.updateVideoSettings(cur.copy(orientationMode = nextMode))
            binding.btnOrientation.text = nextMode.label
        }

        binding.btnMuteAudio.setOnClickListener {
            val svc = webcamService ?: return@setOnClickListener
            val cur = svc.audioSettingsFlow.value
            svc.updateAudioSettings(cur.copy(muted = !cur.muted))
        }

        binding.chipGroupConnection.setOnCheckedStateChangeListener { _, checkedIds ->
            val svc = webcamService ?: return@setOnCheckedStateChangeListener
            val mode = when (checkedIds.firstOrNull()) {
                binding.chipWifi.id -> ConnectionMode.WIFI
                binding.chipUsb.id -> ConnectionMode.USB
                else -> ConnectionMode.DUAL
            }
            svc.updateServerConfig(svc.serverConfigFlow.value.copy(connectionMode = mode))
        }

        binding.toggleSettingsTab.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            binding.panelCameraSettings.visibility = if (checkedId == binding.tabCamera.id) View.VISIBLE else View.GONE
            binding.panelVideoSettings.visibility = if (checkedId == binding.tabVideo.id) View.VISIBLE else View.GONE
            binding.panelAudioSettings.visibility = if (checkedId == binding.tabAudio.id) View.VISIBLE else View.GONE
        }

        binding.switchAutoFocus.setOnCheckedChangeListener { _, isChecked ->
            val svc = webcamService ?: return@setOnCheckedChangeListener
            binding.sliderManualFocus.isEnabled = !isChecked
            val cur = svc.cameraSettingsFlow.value
            svc.updateCameraSettings(cur.copy(autoFocus = isChecked))
        }

        binding.sliderManualFocus.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val svc = webcamService ?: return@addOnChangeListener
            val cur = svc.cameraSettingsFlow.value
            svc.updateCameraSettings(cur.copy(manualFocusNormalized = value))
        }

        binding.sliderExposure.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val svc = webcamService ?: return@addOnChangeListener
            val cur = svc.cameraSettingsFlow.value
            svc.updateCameraSettings(cur.copy(exposureCompensationIndex = value.toInt()))
        }

        val wbOptions = WhiteBalanceOption.entries
        binding.spinnerWhiteBalance.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            wbOptions.map { it.label }
        )
        binding.spinnerWhiteBalance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.cameraSettingsFlow.value
                svc.updateCameraSettings(cur.copy(whiteBalance = wbOptions[position]))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val resOptions = ResolutionOption.PRESETS
        binding.spinnerResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            resOptions.map { it.label }
        )
        binding.spinnerResolution.setSelection(1)
        binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.videoSettingsFlow.value
                if (cur.resolution != resOptions[position]) {
                    svc.updateVideoSettings(cur.copy(resolution = resOptions[position]))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val fpsOptions = listOf(24, 30, 60)
        binding.spinnerFps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            fpsOptions.map { "$it FPS" }
        )
        binding.spinnerFps.setSelection(1)
        binding.spinnerFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.videoSettingsFlow.value
                if (cur.fps != fpsOptions[position]) {
                    svc.updateVideoSettings(cur.copy(fps = fpsOptions[position]))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.sliderBitrate.addOnChangeListener { _, valueMbps, fromUser ->
            binding.tvBitrateValue.text = "${valueMbps.toInt()} Mbps"
            if (!fromUser) return@addOnChangeListener
            val svc = webcamService ?: return@addOnChangeListener
            val cur = svc.videoSettingsFlow.value
            svc.updateVideoSettings(cur.copy(bitrateBps = valueMbps.toInt() * 1_000_000))
        }

        val codecOptions = VideoCodecType.entries
        binding.spinnerCodec.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            codecOptions.map { it.label }
        )
        binding.spinnerCodec.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.videoSettingsFlow.value
                if (cur.codecType != codecOptions[position]) {
                    svc.updateVideoSettings(cur.copy(codecType = codecOptions[position]))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.switchAudioEnabled.setOnCheckedChangeListener { _, isChecked ->
            val svc = webcamService ?: return@setOnCheckedChangeListener
            val cur = svc.audioSettingsFlow.value
            svc.updateAudioSettings(cur.copy(enabled = isChecked))
        }

        val audioSources = AudioSourceOption.entries
        binding.spinnerAudioSource.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            audioSources.map { it.label }
        )
        binding.spinnerAudioSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.audioSettingsFlow.value
                svc.updateAudioSettings(cur.copy(source = audioSources[position]))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val sampleRates = listOf(16000, 44100, 48000)
        binding.spinnerSampleRate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sampleRates.map { "${it / 1000.0} kHz" }
        )
        binding.spinnerSampleRate.setSelection(2)
        binding.spinnerSampleRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.audioSettingsFlow.value
                svc.updateAudioSettings(cur.copy(sampleRate = sampleRates[position]))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val channelConfigs = listOf(
            "Mono (1 Ch)" to AudioFormat.CHANNEL_IN_MONO,
            "Stereo (2 Ch)" to AudioFormat.CHANNEL_IN_STEREO
        )
        binding.spinnerAudioChannels.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            channelConfigs.map { it.first }
        )
        binding.spinnerAudioChannels.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val svc = webcamService ?: return
                val cur = svc.audioSettingsFlow.value
                svc.updateAudioSettings(cur.copy(channelConfig = channelConfigs[position].second))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun observeServiceState() {
        val svc = webcamService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.statsFlow.collect { stats ->
                        binding.tvConnectionStatus.text = stats.statusMessage
                        binding.btnStartStop.text = if (stats.isStreaming) "STOP WEBCAM" else "START WEBCAM"

                        val mode = svc.serverConfigFlow.value.connectionMode
                        val cfg = svc.serverConfigFlow.value
                        binding.tvIpEndpoint.text = when (mode) {
                            ConnectionMode.WIFI -> "Wi-Fi: ${stats.wifiIpAddress}:${cfg.httpPort} (TCP :${cfg.tcpStreamPort})"
                            ConnectionMode.USB -> {
                                val usbAddr = stats.usbIpAddress ?: "127.0.0.1 (via ADB)"
                                "USB: $usbAddr:${cfg.httpPort}"
                            }
                            ConnectionMode.DUAL -> {
                                val usbLabel = if (stats.isUsbConnected) " | USB Ready" else ""
                                "${stats.wifiIpAddress}:${cfg.httpPort}$usbLabel"
                            }
                        }

                        val vid = svc.videoSettingsFlow.value
                        binding.tvHudTelemetry.text = if (stats.isStreaming) {
                            "${vid.resolution.label} | ${stats.currentFps.toInt()} FPS | ${stats.bitrateKbps} kbps | Clients: ${stats.connectedClients}"
                        } else {
                            "${vid.resolution.label} | ${vid.fps} FPS | Standby"
                        }

                        val progress = ((stats.audioLevelDb + 60f) / 60f * 100f).toInt().coerceIn(0, 100)
                        binding.progressAudioLevel.progress = progress
                    }
                }

                launch {
                    svc.cameraSettingsFlow.collect { cam ->
                        binding.btnTorch.text = if (cam.torchEnabled) "Flash ON" else "Flash OFF"
                        binding.btnSwitchCamera.text = if (cam.useFrontCamera) "Front Cam" else "Back Cam"
                        val expRange = svc.exposureRange
                        if (expRange.upper > expRange.lower) {
                            binding.sliderExposure.valueFrom = expRange.lower.toFloat()
                            binding.sliderExposure.valueTo = expRange.upper.toFloat()
                            binding.sliderExposure.value = cam.exposureCompensationIndex.toFloat()
                                .coerceIn(expRange.lower.toFloat(), expRange.upper.toFloat())
                        }
                    }
                }

                launch {
                    svc.audioSettingsFlow.collect { aud ->
                        binding.btnMuteAudio.text = if (aud.muted) "Muted" else "Mic Live"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            webcamService?.attachPreviewView(null)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
