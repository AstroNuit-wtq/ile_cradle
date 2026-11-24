package com.diplomka.app.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.*

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
    private var frameIndex = 0
    private var totalDistance = 0.0
    private var smoothedSpeed = 0f
    private val commandHistory = ArrayDeque<NavigationCommand>(24)

    private var personHits = 0
    private var vehicleHits = 0
    private var motionHits = 0
    private var stopActive = false
    private var lastCommandFrame = -999
    private var lastWeather = "неизвестно"
    private var lastTx = 0.5f
    private var lastTy = 0.82f
    private var lastRoadWidth = 0f

    fun process(bitmap: Bitmap): FrameResult {
        frameIndex++
        val w = bitmap.width
        val h = bitmap.height

        val scale = 280f / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(64)
        val sh = (h * scale).toInt().coerceAtLeast(48)
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
        val strongMotion = motionRects.size >= 2
        if (strongMotion) motionHits = (motionHits + 1).coerceAtMost(8) else motionHits = (motionHits - 1).coerceAtLeast(0)

        val people = detectPeopleHeuristic(luma, sw, sh)
        val vehicles = detectVehiclesHeuristic(luma, sw, sh)
        detections.addAll(people)
        detections.addAll(vehicles)

        if (people.isNotEmpty()) personHits = (personHits + 1).coerceAtMost(10) else personHits = (personHits - 1).coerceAtLeast(0)
        if (vehicles.isNotEmpty()) vehicleHits = (vehicleHits + 1).coerceAtMost(10) else vehicleHits = (vehicleHits - 1).coerceAtLeast(0)

        val wantStop = personHits >= 4 || vehicleHits >= 4 || motionHits >= 6
        if (wantStop) {
            stopActive = true
        } else if (personHits == 0 && vehicleHits == 0 && motionHits <= 1) {
            stopActive = false
        }
        val stopSignal = stopActive

        val (txRaw, tyRaw, roadWidth) = estimateRoadTarget(luma, sw, sh)
        lastTx = 0.7f * lastTx + 0.3f * txRaw
        lastTy = 0.7f * lastTy + 0.3f * tyRaw
        lastRoadWidth = 0.8f * lastRoadWidth + 0.2f * roadWidth

        val displacement = estimateDisplacement(luma, sw, sh)
        val fpsApprox = 12f
        val scaleFactor = 0.05f
        val rawSpeed = (displacement * scaleFactor * fpsApprox).coerceIn(0f, 12f)
        smoothedSpeed = 0.25f * rawSpeed + 0.75f * smoothedSpeed
        if (!stopSignal) {
            totalDistance += smoothedSpeed / fpsApprox
        }

        if (frameIndex % 15 == 0) {
            lastWeather = estimateWeather(pixels, sw, sh)
        }

        if (frameIndex - lastCommandFrame >= 8) {
            lastCommandFrame = frameIndex
            if (stopSignal) {
                val reason = when {
                    personHits >= 4 -> "ЧЕЛОВЕК — СТОП (подтверждено)"
                    vehicleHits >= 4 -> "МАШИНА — СТОП (подтверждено)"
                    else -> "Сильное движение — СТОП"
                }
                commands.add(NavigationCommand(reason, NavigationCommand.Severity.STOP))
            } else {
                val dir = when {
                    lastTx < 0.38f -> "влево"
                    lastTx > 0.62f -> "вправо"
                    else -> "прямо"
                }
                commands.add(
                    NavigationCommand(
                        "Маршрут (NN): $dir  (${(lastTx * 100).toInt()}%)",
                        NavigationCommand.Severity.NAV
                    )
                )
            }
            commands.add(
                NavigationCommand(
                    "Скорость " + String.format("%.1f", smoothedSpeed) +
                        " м/с | Путь " + String.format("%.1f", totalDistance) + " м",
                    NavigationCommand.Severity.INFO
                )
            )
            commands.add(
                NavigationCommand("Погода: $lastWeather", NavigationCommand.Severity.INFO)
            )
            for (c in commands) {
                commandHistory.addFirst(c)
                if (commandHistory.size > 20) commandHistory.removeLast()
            }
        }

        prevLuma = luma

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
            targetX = lastTx,
            targetY = lastTy,
            estimatedSpeedMps = smoothedSpeed,
            totalDistanceM = totalDistance.toFloat(),
            weather = lastWeather,
            roadWidthEstimate = lastRoadWidth,
            commands = commandHistory.toList()
        )
    }

    private fun detectMotion(luma: IntArray, w: Int, h: Int): List<RectF> {
        val prev = prevLuma ?: return emptyList()
        if (prev.size != luma.size) return emptyList()

        val thresh = 40
        val binary = BooleanArray(luma.size)
        var changed = 0
        for (i in luma.indices) {
            if (abs(luma[i] - prev[i]) > thresh) {
                binary[i] = true
                changed++
            }
        }
        if (changed > luma.size * 0.25) return emptyList()
        if (changed < 80) return emptyList()

        val visited = BooleanArray(luma.size)
        val rects = mutableListOf<RectF>()
        val minArea = 900

        for (y in (h * 0.2).toInt() until h step 6) {
            for (x in 8 until w - 8 step 6) {
                val idx = y * w + x
                if (!binary[idx] || visited[idx]) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true
                var area = 0
                while (queue.isNotEmpty() && area < 4000) {
                    val cur = queue.removeFirst()
                    area++
                    val cx = cur % w
                    val cy = cur / w
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                    for (dy in -3..3 step 2) for (dx in -3..3 step 2) {
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
                val bw = maxX - minX
                val bh = maxY - minY
                if (area >= minArea && bw > 12 && bh > 12) {
                    rects.add(RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()))
                }
            }
        }
        return rects.take(3)
    }

    private fun detectPeopleHeuristic(luma: IntArray, w: Int, h: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val step = 16
        for (y in (h * 0.30f).toInt() until (h * 0.85f).toInt() step step) {
            for (x in (w * 0.15f).toInt() until (w * 0.85f).toInt() step step) {
                val localVar = localVariance(luma, w, h, x, y, 6)
                if (localVar < 1800) continue
                val above = if (y > 20) localVariance(luma, w, h, x, y - 16, 5) else 0f
                if (above < 400) continue
                val rw = 14f
                val rh = 36f
                results.add(
                    Detection(
                        "person",
                        RectF(x - rw / 2, y - rh / 2, x + rw / 2, y + rh / 2),
                        0.65f
                    )
                )
            }
        }
        return results
            .distinctBy { (it.rect.centerX() / 24).toInt() to (it.rect.centerY() / 24).toInt() }
            .take(2)
    }

    private fun detectVehiclesHeuristic(luma: IntArray, w: Int, h: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val step = 20
        for (y in (h * 0.55f).toInt() until h - 12 step step) {
            for (x in (w * 0.1f).toInt() until (w * 0.9f).toInt() step step) {
                val localVar = localVariance(luma, w, h, x, y, 10)
                if (localVar !in 600f..2200f) continue
                val rw = 36f
                val rh = 16f
                results.add(
                    Detection(
                        "vehicle",
                        RectF(x - rw / 2, y - rh / 2, x + rw / 2, y + rh / 2),
                        0.6f
                    )
                )
            }
        }
        return results.distinctBy { (it.rect.centerX() / 30).toInt() }.take(2)
    }

    private fun estimateRoadTarget(luma: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val roiY = (h * 0.65f).toInt()
        if (h - roiY < 10) return Triple(0.5f, 0.82f, 0f)

        val edgeXs = mutableListOf<Int>()
        for (y in roiY until h step 4) {
            for (x in 6 until w - 6) {
                val idx = y * w + x
                val gx = abs(luma[idx + 1] - luma[idx - 1])
                if (gx > 45) edgeXs.add(x)
            }
        }
        if (edgeXs.size < 8) return Triple(0.5f, 0.82f, 0f)

        edgeXs.sort()
        val central = edgeXs.filter { it in (w * 0.2).toInt()..(w * 0.8).toInt() }
        val targetX = if (central.isNotEmpty()) {
            central[central.size / 2].toFloat() / w
        } else {
            edgeXs[edgeXs.size / 2].toFloat() / w
        }

        val leftEdge = edgeXs.firstOrNull { it < w / 2 } ?: 0
        val rightEdge = edgeXs.lastOrNull { it > w / 2 } ?: w
        val roadWidthM = ((rightEdge - leftEdge).toFloat() * 0.07f * 1.5f).coerceIn(2f, 18f)

        return Triple(targetX.coerceIn(0.2f, 0.8f), 0.82f, roadWidthM)
    }

    private fun estimateDisplacement(luma: IntArray, w: Int, h: Int): Float {
        val prev = prevLuma ?: return 0f
        if (prev.size != luma.size) return 0f
        var sum = 0.0
        var count = 0
        val step = 10
        for (y in 12 until h - 12 step step) {
            for (x in 12 until w - 12 step step) {
                sum += abs(luma[y * w + x] - prev[y * w + x])
                count++
            }
        }
        return if (count > 0) (sum / count).toFloat() * 0.12f else 0f
    }

    private fun estimateWeather(pixels: IntArray, w: Int, h: Int): String {
        val skyEnd = (h * 0.32f).toInt()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in 0 until skyEnd step 3) {
            for (x in 0 until w step 3) {
                val c = pixels[y * w + x]
                rSum += (c shr 16) and 0xFF
                gSum += (c shr 8) and 0xFF
                bSum += c and 0xFF
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
        personHits = 0
        vehicleHits = 0
        motionHits = 0
        stopActive = false
        lastCommandFrame = -999
    }
}
