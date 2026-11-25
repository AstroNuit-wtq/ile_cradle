package com.diplomka.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.ViewGroup
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

data class CameraOption(
    val id: String,
    val label: String,
    val facing: Int,
    val physicalId: String? = null,
    val zoomRatio: Float? = null
)

fun enumerateCameras(context: Context): List<CameraOption> {
    val opts = mutableListOf<CameraOption>()
    val seen = mutableSetOf<String>()
    try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        fun addLens(logicalId: String, physicalId: String?, facing: Int, minF: Float, tag: String) {
            val key = physicalId ?: logicalId
            if (!seen.add(key)) return
            val label = when {
                facing != CameraCharacteristics.LENS_FACING_BACK &&
                    facing != CameraCharacteristics.LENS_FACING_FRONT -> "USB $key"
                minF < 2.8f || tag == "uw" -> "Ширик $key"
                minF > 5.5f || tag == "tele" -> "Теле $key"
                else -> "Осн $key"
            }
            val zoom = when {
                minF < 2.8f || tag == "uw" -> 0.6f
                minF > 5.5f || tag == "tele" -> 2.0f
                else -> null
            }
            opts.add(CameraOption(logicalId, label, facing, physicalId, zoom))
        }
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) continue
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val minF = focals?.minOrNull() ?: 4f
            addLens(id, null, facing, minF, "main")
            try {
                for (pid in chars.physicalCameraIds) {
                    try {
                        val pc = cm.getCameraCharacteristics(pid)
                        val pf = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4f
                        val tag = when {
                            pf < 2.8f -> "uw"
                            pf > 5.5f -> "tele"
                            else -> "main"
                        }
                        addLens(id, pid, facing, pf, tag)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        for (probe in listOf("2", "3", "4", "5", "6", "7")) {
            try {
                val pc = cm.getCameraCharacteristics(probe)
                val facing = pc.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) continue
                val pf = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4f
                val tag = when {
                    pf < 2.8f -> "uw"
                    pf > 5.5f -> "tele"
                    else -> "main"
                }
                addLens(probe, probe, facing, pf, tag)
            } catch (_: Exception) {}
        }
    } catch (e: Exception) {
        Log.e("Diplomka", "lenses", e)
    }
    if (opts.isEmpty()) {
        opts.add(CameraOption("0", "Основная", CameraCharacteristics.LENS_FACING_BACK))
    }
    return opts.sortedBy { o ->
        when {
            o.label.startsWith("Осн") -> 0
            o.label.startsWith("Ширик") -> 1
            o.label.startsWith("Теле") -> 2
            else -> 3
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun StableCameraPreview(
    lifecycleOwner: LifecycleOwner,
    isRunning: Boolean,
    analysisExecutor: java.util.concurrent.Executor,
    cameraBound: AtomicBoolean,
    cameraId: String?,
    physicalId: String? = null,
    zoomRatio: Float? = null,
    torchOn: Boolean = false,
    onCameraControl: (CameraControl?) -> Unit = {},
    onFrame: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    val runningState = rememberUpdatedState(isRunning)
    val onFrameState = rememberUpdatedState(onFrame)
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(lifecycleOwner, cameraId, physicalId, zoomRatio) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                fun applyPhysicalP(builder: Preview.Builder): Preview.Builder {
                    if (!physicalId.isNullOrBlank()) {
                        Camera2Interop.Extender(builder).setPhysicalCameraId(physicalId)
                    }
                    return builder
                }
                fun applyPhysicalA(builder: ImageAnalysis.Builder): ImageAnalysis.Builder {
                    if (!physicalId.isNullOrBlank()) {
                        Camera2Interop.Extender(builder).setPhysicalCameraId(physicalId)
                    }
                    return builder
                }

                val preview = applyPhysicalP(
                    Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9)
                ).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = applyPhysicalA(
                    ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                ).build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (!runningState.value) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val finalBitmap = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else bitmap
                        onFrameState.value(finalBitmap)
                    } catch (e: Exception) {
                        Log.e("Diplomka", "frame", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                val selector = buildCameraSelectorById(cameraId)
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, analysis
                )
                onCameraControl(camera.cameraControl)
                try { camera.cameraControl.enableTorch(torchOn) } catch (_: Exception) {}
                if (zoomRatio != null) {
                    try {
                        camera.cameraControl.setZoomRatio(zoomRatio)
                    } catch (e: Exception) {
                        Log.w("Diplomka", "zoomRatio $zoomRatio failed", e)
                    }
                }
                cameraBound.set(true)
                Log.i("Diplomka", "bound camera id=$cameraId physical=$physicalId zoom=$zoomRatio")
            } catch (e: Exception) {
                Log.e("Diplomka", "bind id=$cameraId physical=$physicalId", e)
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (!runningState.value) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        try {
                            onFrameState.value(imageProxy.toBitmap())
                        } catch (_: Exception) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                    onCameraControl(camera.cameraControl)
                    cameraBound.set(true)
                } catch (e2: Exception) {
                    Log.e("Diplomka", "fallback bind", e2)
                }
            }
        }
        cameraProviderFuture.addListener(listener, executor)
        onDispose {
            try { cameraProviderFuture.get().unbindAll() } catch (_: Exception) {}
            onCameraControl(null)
            cameraBound.set(false)
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun buildCameraSelectorById(cameraId: String?): CameraSelector {
    if (cameraId.isNullOrBlank()) return CameraSelector.DEFAULT_BACK_CAMERA
    return CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            val match = cameraInfos.filter { info ->
                try {
                    Camera2CameraInfo.from(info).cameraId == cameraId
                } catch (_: Exception) {
                    false
                }
            }
            if (match.isNotEmpty()) match
            else cameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        }
        .build()
}

fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    buffer.rewind()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}
