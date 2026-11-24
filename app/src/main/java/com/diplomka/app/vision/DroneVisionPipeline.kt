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
        val bypassHint: String,
        val freeSectorDeg: Float,
        val nnNextPoint: Pair<Float, Float>
    )

    var strictStopEnabled: Boolean = false
    @Volatile var forceStop: Boolean = false

    private var prevLuma: IntArray? = null
    private var frameIndex = 0
    private var totalDistance = 0.0
    private var smoothedSpeed = 0f
    private val commandHistory = ArrayDeque<NavigationCommand>(24)
    private var motionHits = 0
    private var stopActive = false
    private var lastCommandFrame = -999
    private var lastWeather = "неизвестно"
    private var lastTx = 0.5f
    private var lastTy = 0.82f
    private var lastRoadWidth = 0f
    private var lastFreeSector = 0f

    private val priorHeightM = mapOf(
        "person" to 1.70f,
        "car" to 1.45f,
        "obstacle" to 0.90f,
        "motion" to 1.00f
    )

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
            luma[i] = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
        }
        val detections = mutableListOf<Detection>()
        val commands = mutableListOf<NavigationCommand>()
        val obstacles = detectAndClassify(luma, sw, sh)
        detections.addAll(obstacles)
        val motionRects = detectStrongMotion(luma, sw, sh)
        if (motionRects.isNotEmpty()) {
            motionHits = (motionHits + 1).coerceAtMost(14)
            for (r in motionRects) {
                val cls = classifyBox(r, sw, sh)
                detections.add(Detection(if (cls == "obstacle") "motion" else cls, r, 0.82f, estimateDistance(r, sh, cls), sideOf(r, sw)))
            }
        } else motionHits = (motionHits - 2).coerceAtLeast(0)
        val merged = mergeOverlapping(detections).sortedBy { it.distanceM.takeIf { d -> d > 0 } ?: 99f }
        val nearest = merged.filter { it.distanceM > 0 }.minByOrNull { it.distanceM }
        val nearestDist = nearest?.distanceM ?: -1f
        val (freeDeg, nextPt) = nearestNeighborFreePath(merged, sw, sh)
        lastFreeSector = 0.6f * lastFreeSector + 0.4f * freeDeg
        val bypass = when {
            freeDeg < -18f -> "объезд влево (${freeDeg.toInt()}°)"
            freeDeg > 18f -> "объезд вправо (+${freeDeg.toInt()}°)"
            nearestDist in 0.1f..1.5f -> "осторожно, близко"
            merged.isEmpty() -> "путь свободен"
            else -> "держите курс"
        }
        val closeDanger = nearestDist in 0.15f..1.1f && (nearest?.type == "person" || nearest?.type == "car" || nearest?.type == "motion")
        val wantStop = forceStop || (strictStopEnabled && (motionHits >= 7 || closeDanger || nearestDist in 0.15f..0.7f))
        if (wantStop) stopActive = true
        else if (!forceStop && motionHits <= 1 && (nearestDist < 0 || nearestDist > 1.4f)) stopActive = false
        val stopSignal = stopActive || forceStop
        val (txRaw, tyRaw, roadWidth) = estimateRoadTarget(luma, sw, sh)
        val biasX = (freeDeg / 90f) * 0.22f
        lastTx = (0.55f * lastTx + 0.45f * (txRaw + biasX).coerceIn(0.12f, 0.88f))
        lastTy = 0.7f * lastTy + 0.3f * tyRaw
        lastRoadWidth = 0.85f * lastRoadWidth + 0.15f * roadWidth
        val displacement = estimateDisplacement(luma, sw, sh)
        val rawSpeed = (displacement * 0.04f * 10f).coerceIn(0f, 10f)
        smoothedSpeed = 0.25f * rawSpeed + 0.75f * smoothedSpeed
        if (!stopSignal) totalDistance += smoothedSpeed / 10f
        if (frameIndex % 18 == 0) lastWeather = estimateWeather(pixels, sw, sh)
        if (frameIndex - lastCommandFrame >= 7) {
            lastCommandFrame = frameIndex
            if (stopSignal) commands.add(NavigationCommand(if (forceStop) "СТОП (кнопка)" else "СТОП — препятствие", NavigationCommand.Severity.STOP))
            else {
                val dir = when { lastTx < 0.36f -> "влево"; lastTx > 0.64f -> "вправо"; else -> "прямо" }
                commands.add(NavigationCommand("NN курс: $dir (сектор ${freeDeg.toInt()}°)", NavigationCommand.Severity.NAV))
            }
            if (nearestDist > 0 && nearest != null) {
                commands.add(NavigationCommand("${nearest.type} ≈ " + String.format("%.1f", nearestDist) + " м (" + nearest.side + ")", NavigationCommand.Severity.WARN))
            }
            commands.add(NavigationCommand("Обход: $bypass", NavigationCommand.Severity.NAV))
            if (merged.isNotEmpty()) commands.add(NavigationCommand("Объектов: ${merged.size}", NavigationCommand.Severity.INFO))
            commands.add(NavigationCommand("Скорость " + String.format("%.1f", smoothedSpeed) + " м/с | Путь " + String.format("%.1f", totalDistance) + " м", NavigationCommand.Severity.INFO))
            for (c in commands) { commandHistory.addFirst(c); if (commandHistory.size > 20) commandHistory.removeLast() }
        }
        prevLuma = luma
        val mapped = merged.map {
            Detection(it.type, RectF(it.rect.left / scale, it.rect.top / scale, it.rect.right / scale, it.rect.bottom / scale), it.confidence, it.distanceM, it.side)
        }
        return FrameResult(mapped, stopSignal, lastTx, lastTy, smoothedSpeed, totalDistance.toFloat(), lastWeather, lastRoadWidth, commandHistory.toList(), nearestDist, bypass, lastFreeSector, Pair(nextPt.first, nextPt.second))
    }

    private fun detectAndClassify(luma: IntArray, w: Int, h: Int): List<Detection> {
        val edge = BooleanArray(luma.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = abs(luma[y * w + x + 1] - luma[y * w + x - 1])
            val gy = abs(luma[(y + 1) * w + x] - luma[(y - 1) * w + x])
            edge[y * w + x] = (gx + gy) > 62
        }
        val dil = BooleanArray(luma.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (edge[y * w + x]) for (dy in -1..1) for (dx in -1..1) dil[(y + dy) * w + (x + dx)] = true
        }
        val visited = BooleanArray(luma.size)
        val results = mutableListOf<Detection>()
        val minArea = (w * h * 0.006).toInt().coerceAtLeast(60)
        for (y in (h * 0.12f).toInt() until h step 2) {
            for (x in 2 until w - 2 step 2) {
                val idx = y * w + x
                if (!dil[idx] || visited[idx]) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val q = ArrayDeque<Int>(); q.add(idx); visited[idx] = true
                var area = 0
                while (q.isNotEmpty() && area < 15000) {
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
                if (area >= minArea && bw > 10 && bh > 10) {
                    val pad = 4
                    val rect = RectF((minX - pad).toFloat().coerceAtLeast(0f), (minY - pad).toFloat().coerceAtLeast(0f),
                        (maxX + pad).toFloat().coerceAtMost(w - 1f), (maxY + pad).toFloat().coerceAtMost(h - 1f))
                    val cls = classifyBox(rect, w, h)
                    val conf = when (cls) { "person" -> 0.78f; "car" -> 0.75f; else -> 0.68f }
                    results.add(Detection(cls, rect, conf, estimateDistance(rect, h, cls), sideOf(rect, w)))
                }
            }
        }
        return mergeOverlapping(results).take(10)
    }

    private fun classifyBox(rect: RectF, frameW: Int, frameH: Int): String {
        val bw = rect.width().coerceAtLeast(1f)
        val bh = rect.height().coerceAtLeast(1f)
        val aspect = bw / bh
        val cy = rect.centerY() / frameH
        val areaRatio = (bw * bh) / (frameW * frameH)
        return when {
            aspect < 0.55f && bh > frameH * 0.12f && cy > 0.25f -> "person"
            aspect > 1.25f && aspect < 3.2f && cy > 0.35f && areaRatio > 0.02f -> "car"
            else -> "obstacle"
        }
    }

    private fun estimateDistance(rect: RectF, frameH: Int, type: String): Float {
        val realH = priorHeightM[type] ?: 1.0f
        val hPx = rect.height().coerceAtLeast(4f)
        val fPx = frameH * 1.15f
        val z = (realH * fPx) / hPx
        val cy = rect.centerY() / frameH.coerceAtLeast(1)
        val groundBias = 1.0f - 0.12f * cy
        return (z * groundBias).coerceIn(0.25f, 40f)
    }

    private fun sideOf(rect: RectF, frameW: Int): String {
        val cx = rect.centerX() / frameW.coerceAtLeast(1)
        return when { cx < 0.33f -> "слева"; cx > 0.67f -> "справа"; else -> "центр" }
    }

    private fun nearestNeighborFreePath(dets: List<Detection>, frameW: Int, frameH: Int): Pair<Float, Pair<Float, Float>> {
        val sectors = 9
        val scores = FloatArray(sectors) { 1f }
        for (d in dets) {
            if (d.distanceM <= 0 || d.distanceM > 8f) continue
            val cx = d.rect.centerX() / frameW
            val sector = ((cx * sectors).toInt()).coerceIn(0, sectors - 1)
            val weight = 1f / (d.distanceM + 0.25f)
            scores[sector] -= weight * 1.8f
            if (sector > 0) scores[sector - 1] -= weight * 0.7f
            if (sector < sectors - 1) scores[sector + 1] -= weight * 0.7f
        }
        var best = sectors / 2
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in scores.indices) if (scores[i] > bestScore) { bestScore = scores[i]; best = i }
        val deg = (best - (sectors - 1) / 2f) * (120f / (sectors - 1))
        return Pair(deg, Pair((best + 0.5f) / sectors, 0.72f))
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
        val thresh = 45; var changed = 0
        val binary = BooleanArray(luma.size)
        val y0 = (h * 0.28f).toInt()
        for (y in y0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (abs(luma[i] - prev[i]) > thresh) { binary[i] = true; changed++ }
        }
        val region = (h - y0) * w
        if (changed < 90 || changed > region * 0.25) return emptyList()
        val visited = BooleanArray(luma.size)
        val rects = mutableListOf<RectF>()
        for (y in y0 until h step 5) for (x in 3 until w - 3 step 5) {
            val idx = y * w + x
            if (!binary[idx] || visited[idx]) continue
            var minX = x; var maxX = x; var minY = y; var maxY = y
            val q = ArrayDeque<Int>(); q.add(idx); visited[idx] = true
            var area = 0
            while (q.isNotEmpty() && area < 6000) {
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
            if (area >= 700 && (maxX - minX) > 12 && (maxY - minY) > 12)
                rects.add(RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()))
        }
        return rects.take(5)
    }

    private fun estimateRoadTarget(luma: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val roiY = (h * 0.58f).toInt()
        if (h - roiY < 8) return Triple(0.5f, 0.82f, 0f)
        val edgeXs = mutableListOf<Int>()
        for (y in roiY until h step 3) for (x in 4 until w - 4) {
            if (abs(luma[y * w + x + 1] - luma[y * w + x - 1]) > 42) edgeXs.add(x)
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
        commandHistory.clear(); motionHits = 0; stopActive = false; forceStop = false; lastCommandFrame = -999
    }
}
