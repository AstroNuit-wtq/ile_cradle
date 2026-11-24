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

    var strictStopEnabled: Boolean = false

    private var prevLuma: IntArray? = null
    private var frameIndex = 0
    private var totalDistance = 0.0
    private var smoothedSpeed = 0f
    private val commandHistory = ArrayDeque<NavigationCommand>(20)

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

        val scale = 240f / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(48)
        val sh = (h * scale).toInt().coerceAtLeast(36)
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

        val soft = detectSoftObstacles(luma, sw, sh)
        detections.addAll(soft)

        val motionRects = detectStrongMotion(luma, sw, sh)
        if (motionRects.isNotEmpty()) {
            motionHits = (motionHits + 1).coerceAtMost(12)
            for (r in motionRects) {
                detections.add(Detection("motion", r, 0.75f))
            }
        } else {
            motionHits = (motionHits - 2).coerceAtLeast(0)
        }

        val wantStop = strictStopEnabled && motionHits >= 8
        if (wantStop) stopActive = true
        else if (motionHits <= 1) stopActive = false
        val stopSignal = stopActive

        val (txRaw, tyRaw, roadWidth) = estimateRoadTarget(luma, sw, sh)
        lastTx = 0.75f * lastTx + 0.25f * txRaw
        lastTy = 0.75f * lastTy + 0.25f * tyRaw
        lastRoadWidth = 0.85f * lastRoadWidth + 0.15f * roadWidth

        val displacement = estimateDisplacement(luma, sw, sh)
        val fpsApprox = 10f
        val rawSpeed = (displacement * 0.04f * fpsApprox).coerceIn(0f, 10f)
        smoothedSpeed = 0.2f * rawSpeed + 0.8f * smoothedSpeed
        if (!stopSignal) totalDistance += smoothedSpeed / fpsApprox

        if (frameIndex % 20 == 0) {
            lastWeather = estimateWeather(pixels, sw, sh)
        }

        if (frameIndex - lastCommandFrame >= 10) {
            lastCommandFrame = frameIndex
            if (stopSignal) {
                commands.add(NavigationCommand("СИЛЬНОЕ ДВИЖЕНИЕ — СТОП", NavigationCommand.Severity.STOP))
            } else {
                val dir = when {
                    lastTx < 0.38f -> "влево"
                    lastTx > 0.62f -> "вправо"
                    else -> "прямо"
                }
                commands.add(NavigationCommand("Курс (NN): $dir (${(lastTx * 100).toInt()}%)", NavigationCommand.Severity.NAV))
            }
            if (soft.isNotEmpty()) {
                commands.add(NavigationCommand("Препятствия на кадре: ${soft.size}", NavigationCommand.Severity.WARN))
            }
            commands.add(NavigationCommand("Скорость " + String.format("%.1f", smoothedSpeed) + " м/с | Путь " + String.format("%.1f", totalDistance) + " м", NavigationCommand.Severity.INFO))
            commands.add(NavigationCommand("Погода: $lastWeather", NavigationCommand.Severity.INFO))
            for (c in commands) {
                commandHistory.addFirst(c)
                if (commandHistory.size > 18) commandHistory.removeLast()
            }
        }

        prevLuma = luma

        val mapped = detections.map {
            Detection(it.type, RectF(it.rect.left / scale, it.rect.top / scale, it.rect.right / scale, it.rect.bottom / scale), it.confidence)
        }

        return FrameResult(mapped, stopSignal, lastTx, lastTy, smoothedSpeed, totalDistance.toFloat(), lastWeather, lastRoadWidth, commandHistory.toList())
    }

    private fun detectSoftObstacles(luma: IntArray, w: Int, h: Int): List<Detection> {
        val results = mutableListOf<Detection>()
        val step = 20
        for (y in (h * 0.25f).toInt() until (h * 0.9f).toInt() step step) {
            for (x in (w * 0.1f).toInt() until (w * 0.9f).toInt() step step) {
                val v = localVariance(luma, w, h, x, y, 5)
                if (v < 2500f) continue
                results.add(Detection("obstacle", RectF(x - 10f, y - 10f, x + 10f, y + 10f), (v / 8000f).coerceIn(0.4f, 0.9f)))
            }
        }
        return results.distinctBy { (it.rect.centerX() / 28).toInt() to (it.rect.centerY() / 28).toInt() }.take(6)
    }

    private fun detectStrongMotion(luma: IntArray, w: Int, h: Int): List<RectF> {
        val prev = prevLuma ?: return emptyList()
        if (prev.size != luma.size) return emptyList()
        val thresh = 55
        var changed = 0
        val binary = BooleanArray(luma.size)
        val y0 = (h * 0.45f).toInt()
        for (y in y0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (abs(luma[i] - prev[i]) > thresh) { binary[i] = true; changed++ }
            }
        }
        val region = (h - y0) * w
        if (changed < 120 || changed > region * 0.18) return emptyList()
        val visited = BooleanArray(luma.size)
        val rects = mutableListOf<RectF>()
        for (y in y0 until h step 8) {
            for (x in 6 until w - 6 step 8) {
                val idx = y * w + x
                if (!binary[idx] || visited[idx]) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val q = ArrayDeque<Int>()
                q.add(idx); visited[idx] = true
                var area = 0
                while (q.isNotEmpty() && area < 3500) {
                    val cur = q.removeFirst(); area++
                    val cx = cur % w; val cy = cur / w
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                    for (dy in -4..4 step 2) for (dx in -4..4 step 2) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until w && ny in y0 until h) {
                            val ni = ny * w + nx
                            if (binary[ni] && !visited[ni]) { visited[ni] = true; q.add(ni) }
                        }
                    }
                }
                if (area >= 1100 && (maxX - minX) > 16 && (maxY - minY) > 16) {
                    rects.add(RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()))
                }
            }
        }
        return rects.take(3)
    }

    private fun estimateRoadTarget(luma: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val roiY = (h * 0.65f).toInt()
        if (h - roiY < 8) return Triple(0.5f, 0.82f, 0f)
        val edgeXs = mutableListOf<Int>()
        for (y in roiY until h step 4) {
            for (x in 6 until w - 6) {
                if (abs(luma[y * w + x + 1] - luma[y * w + x - 1]) > 48) edgeXs.add(x)
            }
        }
        if (edgeXs.size < 6) return Triple(0.5f, 0.82f, 0f)
        edgeXs.sort()
        val central = edgeXs.filter { it in (w * 0.2).toInt()..(w * 0.8).toInt() }
        val targetX = (if (central.isNotEmpty()) central[central.size / 2] else edgeXs[edgeXs.size / 2]).toFloat() / w
        val left = edgeXs.firstOrNull { it < w / 2 } ?: 0
        val right = edgeXs.lastOrNull { it > w / 2 } ?: w
        val widthM = ((right - left) * 0.07f * 1.4f).coerceIn(2f, 16f)
        return Triple(targetX.coerceIn(0.2f, 0.8f), 0.82f, widthM)
    }

    private fun estimateDisplacement(luma: IntArray, w: Int, h: Int): Float {
        val prev = prevLuma ?: return 0f
        if (prev.size != luma.size) return 0f
        var sum = 0.0; var n = 0
        val step = 12
        for (y in 14 until h - 14 step step) {
            for (x in 14 until w - 14 step step) {
                sum += abs(luma[y * w + x] - prev[y * w + x]); n++
            }
        }
        return if (n > 0) (sum / n).toFloat() * 0.1f else 0f
    }

    private fun estimateWeather(pixels: IntArray, w: Int, h: Int): String {
        val skyEnd = (h * 0.3f).toInt()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in 0 until skyEnd step 3) {
            for (x in 0 until w step 3) {
                val c = pixels[y * w + x]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF; n++
            }
        }
        if (n == 0) return "неизвестно"
        val brightness = (rSum + gSum + bSum) / (3 * n)
        val blueRatio = bSum.toFloat() / (rSum + gSum + bSum + 1)
        return when {
            brightness > 180 && blueRatio > 0.38 -> "ясно / солнечно"
            brightness > 140 && blueRatio > 0.33 -> "переменная облачность"
            brightness > 90 -> "облачно"
            brightness > 50 -> "пасмурно / сумерки"
            else -> "темно / ночь"
        }
    }

    private fun localVariance(luma: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int): Float {
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (dy in -radius..radius step 2) {
            for (dx in -radius..radius step 2) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until w && y in 0 until h) {
                    val v = luma[y * w + x].toDouble(); sum += v; sumSq += v * v; n++
                }
            }
        }
        if (n < 4) return 0f
        val mean = sum / n
        return ((sumSq / n) - mean * mean).toFloat()
    }

    fun reset() {
        prevLuma = null; frameIndex = 0; totalDistance = 0.0; smoothedSpeed = 0f
        commandHistory.clear(); motionHits = 0; stopActive = false; lastCommandFrame = -999
    }
}
