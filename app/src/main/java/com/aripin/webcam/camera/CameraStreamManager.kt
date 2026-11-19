package com.aripin.webcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.aripin.webcam.encoder.VideoHardwareEncoder
import com.aripin.webcam.model.CameraSettings
import com.aripin.webcam.model.OrientationMode
import com.aripin.webcam.model.VideoCodecType
import com.aripin.webcam.model.VideoSettings
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraStreamManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onH264Frame: (data: ByteArray, ptsUs: Long, isConfig: Boolean) -> Unit,
    private val onMjpegFrame: (jpegData: ByteArray, ptsUs: Long) -> Unit,
    private val onHardwareCapabilitiesDetected: (
        hasFlash: Boolean,
        minFocusDist: Float,
        exposureRange: Range<Int>,
        maxZoom: Float
    ) -> Unit
) {
    companion object {
        private const val TAG = "CameraStreamManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null
    private var h264Encoder: VideoHardwareEncoder? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var currentVideoSettings = VideoSettings()
    private var currentCameraSettings = CameraSettings()
    private var isStreamingActive = false

    private var minFocusDistanceDiopters = 0f

    fun attachPreviewView(view: PreviewView?) {
        this.previewView = view
        rebindUseCases()
    }

    fun startCamera(videoSettings: VideoSettings, cameraSettings: CameraSettings, streaming: Boolean) {
        this.currentVideoSettings = videoSettings
        this.currentCameraSettings = cameraSettings
        this.isStreamingActive = streaming

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            rebindUseCases()
        }, mainExecutor)
    }

    fun updateVideoSettings(newSettings: VideoSettings, streaming: Boolean) {
        val needsRebind = newSettings.resolution != currentVideoSettings.resolution ||
            newSettings.fps != currentVideoSettings.fps ||
            newSettings.codecType != currentVideoSettings.codecType ||
            newSettings.orientationMode != currentVideoSettings.orientationMode ||
            streaming != isStreamingActive

        if (!needsRebind && newSettings.bitrateBps != currentVideoSettings.bitrateBps) {
            h264Encoder?.updateBitrate(newSettings.bitrateBps)
        }

        currentVideoSettings = newSettings
        isStreamingActive = streaming

        if (needsRebind) {
            rebindUseCases()
        }
    }

    fun updateCameraSettings(newSettings: CameraSettings) {
        val switchLens = newSettings.useFrontCamera != currentCameraSettings.useFrontCamera
        currentCameraSettings = newSettings
        if (switchLens) {
            rebindUseCases()
        } else {
            applyLiveCamera2Controls()
        }
    }

    fun triggerTapToFocus(x: Float, y: Float) {
        val view = previewView ?: return
        val camera = boundCamera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun rebindUseCases() {
        val provider = cameraProvider ?: return

        try {
            provider.unbindAll()
            h264Encoder?.stop()
            h264Encoder = null

            val lensFacing = if (currentCameraSettings.useFrontCamera) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val targetWidth = if (currentVideoSettings.orientationMode == OrientationMode.PORTRAIT) {
                currentVideoSettings.resolution.height
            } else {
                currentVideoSettings.resolution.width
            }
            val targetHeight = if (currentVideoSettings.orientationMode == OrientationMode.PORTRAIT) {
                currentVideoSettings.resolution.width
            } else {
                currentVideoSettings.resolution.height
            }

            val targetSize = Size(targetWidth, targetHeight)
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val targetRotation = when (currentVideoSettings.orientationMode) {
                OrientationMode.PORTRAIT -> Surface.ROTATION_0
                OrientationMode.LANDSCAPE -> Surface.ROTATION_90
                OrientationMode.AUTO -> previewView?.display?.rotation ?: Surface.ROTATION_0
            }

            val useCases = mutableListOf<androidx.camera.core.UseCase>()

            previewView?.let { view ->
                val uiPreview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(targetRotation)
                    .build()
                uiPreview.surfaceProvider = view.surfaceProvider
                useCases.add(uiPreview)
            }

            if (isStreamingActive && currentVideoSettings.codecType == VideoCodecType.H264_HARDWARE) {
                val encoder = VideoHardwareEncoder(
                    width = targetWidth,
                    height = targetHeight,
                    fps = currentVideoSettings.fps,
                    bitrateBps = currentVideoSettings.bitrateBps,
                    onEncodedNal = onH264Frame
                )
                val encoderSurface = encoder.start()
                if (encoderSurface != null) {
                    h264Encoder = encoder
                    val streamPreview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(targetRotation)
                        .build()
                    streamPreview.setSurfaceProvider { request ->
                        request.provideSurface(encoderSurface, analysisExecutor) {}
                    }
                    useCases.add(streamPreview)
                }
            }

            if (isStreamingActive) {
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(targetRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrameToMjpeg(imageProxy)
                }
                useCases.add(analysis)
            }

            if (useCases.isEmpty()) return

            val camera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
            boundCamera = camera

            val cam2Info = Camera2CameraInfo.from(camera.cameraInfo)
            minFocusDistanceDiopters = cam2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            val hasFlash = camera.cameraInfo.hasFlashUnit()
            val exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
            val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 4.0f

            onHardwareCapabilitiesDetected(hasFlash, minFocusDistanceDiopters, exposureRange, maxZoom)
            applyLiveCamera2Controls()

        } catch (e: Exception) {
            Log.e(TAG, "Error binding CameraX use cases: ${e.message}", e)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyLiveCamera2Controls() {
        val camera = boundCamera ?: return

        if (camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(currentCameraSettings.torchEnabled)
        }

        camera.cameraControl.setZoomRatio(currentCameraSettings.zoomRatio.coerceAtLeast(1.0f))

        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        val clampedExposure = currentCameraSettings.exposureCompensationIndex.coerceIn(range.lower, range.upper)
        camera.cameraControl.setExposureCompensationIndex(clampedExposure)

        val cam2Control = Camera2CameraControl.from(camera.cameraControl)
        val optionsBuilder = CaptureRequestOptions.Builder()

        val fps = currentVideoSettings.fps
        optionsBuilder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(fps, fps)
        )

        optionsBuilder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            currentCameraSettings.whiteBalance.awbMode
        )

        if (currentCameraSettings.autoFocus || minFocusDistanceDiopters <= 0f) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            val diopters = currentCameraSettings.manualFocusNormalized.coerceIn(0f, 1f) * minFocusDistanceDiopters
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                diopters
            )
        }

        cam2Control.setCaptureRequestOptions(optionsBuilder.build())
    }

    private fun processFrameToMjpeg(imageProxy: ImageProxy) {
        try {
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
            val nv21 = yuv420888ToNv21(imageProxy)
            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                currentVideoSettings.jpegQuality,
                out
            )
            onMjpegFrame(out.toByteArray(), ptsUs)
        } catch (e: Exception) {
            Log.w(TAG, "MJPEG frame compression error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                yBuffer.position(r * yRowStride)
                yBuffer.get(row, 0, width)
                System.arraycopy(row, 0, nv21, pos, width)
                pos += width
            }
        }

        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2

        for (r in 0 until uvHeight) {
            for (c in 0 until uvWidth) {
                val uvIndex = r * uvRowStride + c * uvPixelStride
                nv21[pos++] = vBuffer.get(uvIndex)
                nv21[pos++] = uBuffer.get(uvIndex)
            }
        }
        return nv21
    }

    fun release() {
        h264Encoder?.stop()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
