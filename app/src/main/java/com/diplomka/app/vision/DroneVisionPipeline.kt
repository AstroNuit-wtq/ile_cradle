package com.diplomka.app.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.*

/**
 * Unified vision pipeline based on the original diploma Python scripts:
 * - detect_objects.py / hog_detection.py  -> people, vehicles, buildings, motion
 * - yellow_road.py / width_road.py        -> road center / nearest-neighbor target
 * - time_and_distance.py                  -> optical-flow style distance & speed
 * - simple weather estimation from image statistics
 */
class DroneVisionPipeline {

    data class Detection(
        val type: String,
        val rect: RectF,
        val confidence: Float
    )

    data class NavigationCommand(
        val text: String,
        val severity: Severity,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Severity { INFO, WARN, STOP, NAV }
    }

    data class FrameResult(
        val detections: List<Detection>,
        val stopSignal: Boolean,
        val targetX: Float,
        val targetY: Float,
        val estimatedSpeedMps: Float,
        val totalDistanceM: Float,
        val weather: String,
        val roadWidthEstimate: Float,
        val commands: List<NavigationCommand>
    )

    private var prevLuma: IntArray? = null
    private var prevWidth = 0
    private var prevHeight = 0
    private var frameIndex = 0
    private var totalDistance = 0.0
    private var smoothedSpeed = 0f
    private val commandHistory = ArrayDeque<NavigationCommand>(40)

    fun process(bitmap: Bitmap): FrameResult {
        frameIndex++
        val w = bitmap.width
        val h = bitmap.height

        val scale = 320f / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(80)
        val sh = (h * scale).toInt().coerceAtLeast(60)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)

        val luma = IntArray(sw * sh)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        val detections = mutableListOf<Detection>()
        val commands = mutableListOf<NavigationCommand>()

        val motionRects = detectMotion(luma, sw, sh)
        for (r in motionRects) {
            detections.add(Detection("motion", r, 0.7f))
        }

        val people = detectPeopleHeuristic(luma, pixels, sw, sh)
        detections.addAll(people)
        val vehicles = detectVehiclesHeuristic(luma, pixels, sw, sh)
        detections.addAll(vehicles)
        val buildings = detectBuildingsHeuristic(luma, sw, sh)
        detections.addAll(buildings)

        val hasPerson = people.isNotEmpty()
        val hasVehicle = vehicles.isNotEmpty()
        val stopSignal = hasPerson || hasVehicle || motionRects.size > 3

        val (tx, ty, roadWidth) = estimateRoadTarget(luma, sw, sh)

        val displacement = estimateDisplacement(luma, sw, sh)
        val fpsApprox = 15f
        val scaleFactor = 0.06f
        val rawSpeed = (displacement * scaleFactor * fpsApprox).coerceIn(0f, 15f)
        smoothedSpeed = 0.3f * rawSpeed + 0.7f * smoothedSpeed
        totalDistance += smoothedSpeed / fpsApprox

        val weather = estimateWeather(pixels, sw, sh)

        if (stopSignal) {
            val reason = when {
                hasPerson -> "ЧЕЛОВЕК обнаружен — СТОП"
                hasVehicle -> "МАШИНА обнаружена — СТОП"
                else -> "Движение / активность — СТОП"
            }
            commands.add(NavigationCommand(reason, NavigationCommand.Severity.STOP))
        } else {
            val dir = when {
                tx < 0.4f -> "влево"
                tx > 0.6f -> "вправо"
                else -> "прямо"
            }
            commands.add(
                NavigationCommand(
                    "Следующая точка (NN): $dir  (${(tx * 100).toInt()}%, ${(ty * 100).toInt()}%)",
                    NavigationCommand.Severity.NAV
                )
            )
            if (roadWidth > 0) {
                commands.add(
                    NavigationCommand(
                        "Ширина дороги ≈ " + String.format("%.1f", roadWidth) + " м",
                        NavigationCommand.Severity.INFO
                    )
                )
            }
        }

        commands.add(
            NavigationCommand(
                "Скорость ≈ " + String.format("%.1f", smoothedSpeed) + " м/с  |  Дистанция " + String.format("%.1f", totalDistance) + " м",
                NavigationCommand.Severity.INFO
            )
        )
        commands.add(
            NavigationCommand("Погода (визуально): $weather", NavigationCommand.Severity.INFO)
        )
        commands.add(
            NavigationCommand(
                "Кадр #$frameIndex  |  детекции: ${detections.size}",
                NavigationCommand.Severity.INFO
            )
        )

        for (c in commands) {
            commandHistory.addFirst(c)
            if (commandHistory.size > 30) commandHistory.removeLast()
        }

        prevLuma = luma
        prevWidth = sw
        prevHeight = sh

        val mapped = detections.map {
            Detection(
                it.type,
                RectF(
                    it.rect.left / scale,
                    it.rect.top / scale,
                    it.rect.right / scale,
                    it.rect.bottom / scale
                ),
                it.confidence
            )
        }

        return FrameResult(
            detections = mapped,
            stopSignal = stopSignal,
            targetX = tx,
            targetY = ty,
            estimatedSpeedMps = smoothedSpeed,
            totalDistanceM = totalDistance.toFloat(),
            weather = weather,
            roadWidthEstimate = roadWidth,
            commands = commandHistory.toList()
        )
    }

    private fun detectMotion(luma: IntArray, w: Int, h: Int): List<RectF> {
        val prev = prevLuma ?: return emptyList()
        if (prev.size != luma.size) return emptyList()

        val thresh = 28
        val binary = BooleanArray(luma.size)
        for (i in luma.indices) {
            binary[i] = abs(luma[i] - prev[i]) > thresh
        }

        val visited = BooleanArray(luma.size)
        val rects = mutableListOf<RectF>()
        val minArea = 400

        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                val idx = y * w + x
                if (!binary[idx] || visited[idx]) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true
                var area = 0
                while (queue.isNotEmpty() && area < 5000) {
                    val cur = queue.removeFirst()
                    area++
                    val cx = cur % w
                    val cy = cur / w
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                    for (dy in -2..2) for (dx in -2..2) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val ni = ny * w + nx
                            if (binary[ni] && !visited[ni]) {
                                visited[ni] = true
                                queue.add(ni)
                            }
                        }
                    }
                }
                if (area >= minArea) {
                    rects.add(RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()))
                }
            }
        }
        return rects.take(8)
    }

    private fun detectPeopleHeuristic(luma: IntArray, pixels: IntArray, w: Int, h: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val step = 12
        for (y in (h * 0.25f).toInt() until h - 20 step step) {
            for (x in 10 until w - 20 step step) {
                val localVar = localVariance(luma, w, h, x, y, 8)
                if (localVar > 900) {
                    val rw = 18f; val rh = 40f
                    results.add(
                        Detection(
                            "person",
                            RectF(x - rw / 2, y - rh / 2, x + rw / 2, y + rh / 2),
                            0.55f + (localVar / 5000f).coerceAtMost(0.35f)
                        )
                    )
                }
            }
        }
        return results.distinctBy { (it.rect.centerX() / 20).toInt() to (it.rect.centerY() / 20).toInt() }
            .take(4)
    }

    private fun detectVehiclesHeuristic(luma: IntArray, pixels: IntArray, w: Int, h: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val step = 16
        for (y in (h * 0.55f).toInt() until h - 15 step step) {
            for (x in 15 until w - 30 step step) {
                val localVar = localVariance(luma, w, h, x, y, 12)
                if (localVar in 400f..2500f) {
                    val rw = 40f; val rh = 18f
                    results.add(
                        Detection(
                            "vehicle",
                            RectF(x - rw / 2, y - rh / 2, x + rw / 2, y + rh / 2),
                            0.6f
                        )
                    )
                }
            }
        }
        return results.distinctBy { (it.rect.centerX() / 25).toInt() }.take(3)
    }

    private fun detectBuildingsHeuristic(luma: IntArray, w: Int, h: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val step = 24
        for (y in 10 until (h * 0.5f).toInt() step step) {
            for (x in 20 until w - 40 step step) {
                val localVar = localVariance(luma, w, h, x, y, 16)
                if (localVar < 350) {
                    results.add(
                        Detection(
                            "building",
                            RectF(x - 25f, y - 30f, x + 25f, y + 30f),
                            0.5f
                        )
                    )
                }
            }
        }
        return results.distinctBy { (it.rect.centerX() / 40).toInt() }.take(4)
    }

    private fun estimateRoadTarget(luma: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val roiY = (h * 0.65f).toInt()
        val roiH = h - roiY
        if (roiH < 10) return Triple(0.5f, 0.8f, 0f)

        val edgeXs = mutableListOf<Int>()
        for (y in roiY until h step 3) {
            for (x in 5 until w - 5) {
                val idx = y * w + x
                val gx = abs(luma[idx + 1] - luma[idx - 1])
                if (gx > 40) edgeXs.add(x)
            }
        }

        if (edgeXs.isEmpty()) return Triple(0.5f, 0.85f, 0f)

        edgeXs.sort()
        val central = edgeXs.filter { it in (w * 0.2).toInt()..(w * 0.8).toInt() }
        val targetX = if (central.isNotEmpty()) {
            central[central.size / 2].toFloat() / w
        } else {
            edgeXs[edgeXs.size / 2].toFloat() / w
        }

        val leftEdge = edgeXs.firstOrNull { it < w / 2 } ?: 0
        val rightEdge = edgeXs.lastOrNull { it > w / 2 } ?: w
        val pixelWidth = (rightEdge - leftEdge).toFloat()
        val roadWidthM = (pixelWidth * 0.075f * 1.6f).coerceIn(2f, 20f)

        return Triple(targetX.coerceIn(0.15f, 0.85f), 0.82f, roadWidthM)
    }

    private fun estimateDisplacement(luma: IntArray, w: Int, h: Int): Float {
        val prev = prevLuma ?: return 0f
        if (prev.size != luma.size) return 0f

        var sum = 0.0
        var count = 0
        val step = 8
        for (y in 10 until h - 10 step step) {
            for (x in 10 until w - 10 step step) {
                val idx = y * w + x
                sum += abs(luma[idx] - prev[idx])
                count++
            }
        }
        return if (count > 0) (sum / count).toFloat() * 0.15f else 0f
    }

    private fun estimateWeather(pixels: IntArray, w: Int, h: Int): String {
        val skyEnd = (h * 0.35f).toInt()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in 0 until skyEnd step 2) {
            for (x in 0 until w step 2) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                rSum += r; gSum += g; bSum += b
                n++
            }
        }
        if (n == 0) return "неизвестно"
        val avgR = rSum / n
        val avgG = gSum / n
        val avgB = bSum / n
        val brightness = (avgR + avgG + avgB) / 3
        val blueRatio = avgB.toFloat() / (avgR + avgG + avgB + 1)

        return when {
            brightness > 180 && blueRatio > 0.38 -> "ясно / солнечно"
            brightness > 140 && blueRatio > 0.33 -> "переменная облачность"
            brightness > 90 -> "облачно"
            brightness > 50 -> "пасмурно / сумерки"
            else -> "темно / ночь"
        }
    }

    private fun localVariance(luma: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int): Float {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (dy in -radius..radius step 2) {
            for (dx in -radius..radius step 2) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until w && y in 0 until h) {
                    val v = luma[y * w + x].toDouble()
                    sum += v
                    sumSq += v * v
                    n++
                }
            }
        }
        if (n < 4) return 0f
        val mean = sum / n
        return ((sumSq / n) - mean * mean).toFloat()
    }

    fun reset() {
        prevLuma = null
        frameIndex = 0
        totalDistance = 0.0
        smoothedSpeed = 0f
        commandHistory.clear()
    }
}
