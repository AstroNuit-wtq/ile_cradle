package com.diplomka.app.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.*

class DroneVisionPipeline {

    data class Detection(
        val type: String,
        val rect: RectF,
        val confidence: Float,
        val distanceM: Float = -1f,
        val side: String = "center"
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
        val commands: List<NavigationCommand>,
        val nearestDistanceM: Float,
        val bypassHint: String
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
        val scale = 280f / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(64)
        val sh = (h * scale).toInt().coerceAtLeast(48)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val luma = IntArray(sw * sh)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        for (i in pixels.indices) {
            val c = pixels[i]
            luma[i] = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
        }
        val detections = mutableListOf<Detection>()
        val commands = mutableListOf<NavigationCommand>()
        val obstacles = detectFullObstacles(luma, sw, sh)
        detections.addAll(obstacles)
        val motionRects = detectStrongMotion(luma, sw, sh)
        if (motionRects.isNotEmpty()) {
            motionHits = (motionHits + 1).coerceAtMost(12)
            for (r in motionRects) {
                detections.add(Detection("motion", r, 0.8f, estimateDistance(r, sh), sideOf(r, sw)))
            }
        } else motionHits = (motionHits - 2).coerceAtLeast(0)
        val nearest = detections.filter { it.distanceM > 0 }.minByOrNull { it.distanceM }
        val nearestDist = nearest?.distanceM ?: -1f
        val bypass = computeBypass(detections, sw)
        val wantStop = strictStopEnabled && (motionHits >= 8 || (nearestDist in 0.1f..0.8f))
        if (wantStop) stopActive = true
        else if (motionHits <= 1 && (nearestDist < 0 || nearestDist > 1.2f)) stopActive = false
        val stopSignal = stopActive
        val (txRaw, tyRaw, roadWidth) = estimateRoadTarget(luma, sw, sh)
        val biasX = when {
            bypass.contains("влево") -> -0.12f
            bypass.contains("вправо") -> 0.12f
            else -> 0f
        }
        lastTx = (0.7f * lastTx + 0.3f * (txRaw + biasX)).coerceIn(0.15f, 0.85f)
        lastTy = 0.75f * lastTy + 0.25f * tyRaw
        lastRoadWidth = 0.85f * lastRoadWidth + 0.15f * roadWidth
        val displacement = estimateDisplacement(luma, sw, sh)
        val rawSpeed = (displacement * 0.04f * 10f).coerceIn(0f, 10f)
        smoothedSpeed = 0.2f * rawSpeed + 0.8f * smoothedSpeed
        if (!stopSignal) totalDistance += smoothedSpeed / 10f
        if (frameIndex % 20 == 0) lastWeather = estimateWeather(pixels, sw, sh)
        if (frameIndex - lastCommandFrame >= 8) {
            lastCommandFrame = frameIndex
            if (stopSignal) commands.add(NavigationCommand("СТОП — близко / движение", NavigationCommand.Severity.STOP))
            else {
                val dir = when { lastTx < 0.38f -> "влево"; lastTx > 0.62f -> "вправо"; else -> "прямо" }
                commands.add(NavigationCommand("Курс: $dir", NavigationCommand.Severity.NAV))
            }
            if (nearestDist > 0) commands.add(NavigationCommand("Ближайший ≈ " + String.format("%.1f", nearestDist) + " м (" + (nearest?.side ?: "?") + ")", NavigationCommand.Severity.WARN))
            if (bypass.isNotEmpty()) commands.add(NavigationCommand("Обход: $bypass", NavigationCommand.Severity.NAV))
            if (obstacles.isNotEmpty()) commands.add(NavigationCommand("Препятствий: ${obstacles.size}", NavigationCommand.Severity.INFO))
            commands.add(NavigationCommand("Скорость " + String.format("%.1f", smoothedSpeed) + " м/с | Путь " + String.format("%.1f", totalDistance) + " м", NavigationCommand.Severity.INFO))
            for (c in commands) { commandHistory.addFirst(c); if (commandHistory.size > 18) commandHistory.removeLast() }
        }
        prevLuma = luma
        val mapped = detections.map {
            Detection(it.type, RectF(it.rect.left / scale, it.rect.top / scale, it.rect.right / scale, it.rect.bottom / scale), it.confidence, it.distanceM, it.side)
        }
        return FrameResult(mapped, stopSignal, lastTx, lastTy, smoothedSpeed, totalDistance.toFloat(), lastWeather, lastRoadWidth, commandHistory.toList(), nearestDist, bypass)
    }

    private fun detectFullObstacles(luma: IntArray, w: Int, h: Int): List<Detection> {
        val edge = BooleanArray(luma.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = abs(luma[y * w + x + 1] - luma[y * w + x - 1])
            val gy = abs(luma[(y + 1) * w + x] - luma[(y - 1) * w + x])
            edge[y * w + x] = (gx + gy) > 70
        }
        val dil = BooleanArray(luma.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (edge[y * w + x]) for (dy in -1..1) for (dx in -1..1) dil[(y + dy) * w + (x + dx)] = true
        }
        val visited = BooleanArray(luma.size)
        val results = mutableListOf<Detection>()
        val minArea = (w * h * 0.008).toInt().coerceAtLeast(80)
        for (y in (h * 0.15f).toInt() until h step 3) {
            for (x in 2 until w - 2 step 3) {
                val idx = y * w + x
                if (!dil[idx] || visited[idx]) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val q = ArrayDeque<Int>(); q.add(idx); visited[idx] = true
                var area = 0
                while (q.isNotEmpty() && area < 12000) {
                    val cur = q.removeFirst(); area++
                    val cx = cur % w; val cy = cur / w
                    minX = min(minX, cx); maxX = max(maxX, cx); minY = min(minY, cy); maxY = max(maxY, cy)
                    for (dy in -2..2) for (dx in -2..2) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val ni = ny * w + nx
                            if (dil[ni] && !visited[ni]) { visited[ni] = true; q.add(ni) }
                        }
                    }
                }
                val bw = maxX - minX; val bh = maxY - minY
                if (area >= minArea && bw > 12 && bh > 12) {
                    val pad = 3
                    val rect = RectF((minX - pad).toFloat().coerceAtLeast(0f), (minY - pad).toFloat().coerceAtLeast(0f),
                        (maxX + pad).toFloat().coerceAtMost(w - 1f), (maxY + pad).toFloat().coerceAtMost(h - 1f))
                    results.add(Detection("obstacle", rect, 0.7f, estimateDistance(rect, h), sideOf(rect, w)))
                }
            }
        }
        return mergeOverlapping(results).take(8)
    }

    private fun mergeOverlapping(list: List<Detection>): List<Detection> {
        if (list.isEmpty()) return list
        val sorted = list.sortedByDescending { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }
        val kept = mutableListOf<Detection>()
        for (d in sorted) {
            val overlaps = kept.any { k ->
                val a = d.rect; val b = k.rect
                a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
            }
            if (!overlaps) kept.add(d)
        }
        return kept
    }

    private fun detectStrongMotion(luma: IntArray, w: Int, h: Int): List<RectF> {
        val prev = prevLuma ?: return emptyList()
        if (prev.size != luma.size) return emptyList()
        val thresh = 48; var changed = 0
        val binary = BooleanArray(luma.size)
        val y0 = (h * 0.3f).toInt()
        for (y in y0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (abs(luma[i] - prev[i]) > thresh) { binary[i] = true; changed++ }
        }
        val region = (h - y0) * w
        if (changed < 100 || changed > region * 0.22) return emptyList()
        val visited = BooleanArray(luma.size)
        val rects = mutableListOf<RectF>()
        for (y in y0 until h step 6) for (x in 4 until w - 4 step 6) {
            val idx = y * w + x
            if (!binary[idx] || visited[idx]) continue
            var minX = x; var maxX = x; var minY = y; var maxY = y
            val q = ArrayDeque<Int>(); q.add(idx); visited[idx] = true
            var area = 0
            while (q.isNotEmpty() && area < 5000) {
                val cur = q.removeFirst(); area++
                val cx = cur % w; val cy = cur / w
                minX = min(minX, cx); maxX = max(maxX, cx); minY = min(minY, cy); maxY = max(maxY, cy)
                for (dy in -3..3) for (dx in -3..3) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx in 0 until w && ny in y0 until h) {
                        val ni = ny * w + nx
                        if (binary[ni] && !visited[ni]) { visited[ni] = true; q.add(ni) }
                    }
                }
            }
            if (area >= 900 && (maxX - minX) > 14 && (maxY - minY) > 14)
                rects.add(RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()))
        }
        return rects.take(4)
    }

    private fun estimateDistance(rect: RectF, frameH: Int): Float {
        val cy = rect.centerY() / frameH.coerceAtLeast(1)
        val heightRatio = (rect.height() / frameH.coerceAtLeast(1)).coerceIn(0.02f, 0.9f)
        return ((1.2f / (heightRatio + 0.05f)) * (1.3f - cy)).coerceIn(0.3f, 25f)
    }

    private fun sideOf(rect: RectF, frameW: Int): String {
        val cx = rect.centerX() / frameW.coerceAtLeast(1)
        return when { cx < 0.35f -> "слева"; cx > 0.65f -> "справа"; else -> "центр" }
    }

    private fun computeBypass(dets: List<Detection>, frameW: Int): String {
        if (dets.isEmpty()) return "путь свободен"
        val near = dets.filter { it.distanceM in 0.1f..4f }
        if (near.isEmpty()) return "путь свободен"
        var leftBlock = 0f; var rightBlock = 0f; var centerBlock = 0f
        for (d in near) {
            val weight = 1f / (d.distanceM + 0.3f)
            when (d.side) { "слева" -> leftBlock += weight; "справа" -> rightBlock += weight; else -> centerBlock += weight }
        }
        return when {
            centerBlock > leftBlock && centerBlock > rightBlock -> if (leftBlock < rightBlock) "объезд влево" else "объезд вправо"
            leftBlock > rightBlock * 1.3f -> "держитесь правее"
            rightBlock > leftBlock * 1.3f -> "держитесь левее"
            else -> "осторожно, объекты рядом"
        }
    }

    private fun estimateRoadTarget(luma: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val roiY = (h * 0.6f).toInt()
        if (h - roiY < 8) return Triple(0.5f, 0.82f, 0f)
        val edgeXs = mutableListOf<Int>()
        for (y in roiY until h step 3) for (x in 4 until w - 4) {
            if (abs(luma[y * w + x + 1] - luma[y * w + x - 1]) > 45) edgeXs.add(x)
        }
        if (edgeXs.size < 5) return Triple(0.5f, 0.82f, 0f)
        edgeXs.sort()
        val central = edgeXs.filter { it in (w * 0.2).toInt()..(w * 0.8).toInt() }
        val targetX = (if (central.isNotEmpty()) central[central.size / 2] else edgeXs[edgeXs.size / 2]).toFloat() / w
        val left = edgeXs.firstOrNull { it < w / 2 } ?: 0
        val right = edgeXs.lastOrNull { it > w / 2 } ?: w
        return Triple(targetX.coerceIn(0.2f, 0.8f), 0.82f, ((right - left) * 0.07f * 1.4f).coerceIn(2f, 16f))
    }

    private fun estimateDisplacement(luma: IntArray, w: Int, h: Int): Float {
        val prev = prevLuma ?: return 0f
        if (prev.size != luma.size) return 0f
        var sum = 0.0; var n = 0
        for (y in 14 until h - 14 step 12) for (x in 14 until w - 14 step 12) {
            sum += abs(luma[y * w + x] - prev[y * w + x]); n++
        }
        return if (n > 0) (sum / n).toFloat() * 0.1f else 0f
    }

    private fun estimateWeather(pixels: IntArray, w: Int, h: Int): String {
        val skyEnd = (h * 0.28f).toInt()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in 0 until skyEnd step 3) for (x in 0 until w step 3) {
            val c = pixels[y * w + x]
            rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF; n++
        }
        if (n == 0) return "неизвестно"
        val brightness = (rSum + gSum + bSum) / (3 * n)
        val blueRatio = bSum.toFloat() / (rSum + gSum + bSum + 1)
        return when {
            brightness > 180 && blueRatio > 0.38 -> "ясно"
            brightness > 140 -> "переменная облачность"
            brightness > 90 -> "облачно"
            brightness > 50 -> "пасмурно"
            else -> "темно"
        }
    }

    fun reset() {
        prevLuma = null; frameIndex = 0; totalDistance = 0.0; smoothedSpeed = 0f
        commandHistory.clear(); motionHits = 0; stopActive = false; lastCommandFrame = -999
    }
}
