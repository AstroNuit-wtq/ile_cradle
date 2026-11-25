package com.diplomka.app.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.*

/** Tuned for Redmi 9 / Note 13 / Note 13 Pro. Low-light adaptive + proximity STOP. */
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
        val nnNextPoint: Pair<Float, Float>,
        val sceneBrightness: Float
    )

    var strictStopEnabled: Boolean = true
    @Volatile var forceStop: Boolean = false

    private var prevLuma: IntArray? = null
    private var frameIndex = 0
    private var totalDistance = 0.0
    private var smoothedSpeed = 0f
    private val commandHistory = ArrayDeque<NavigationCommand>(24)
    private var motionHits = 0
    private var stopActive = false
    private var stopHoldFrames = 0
    private var lastCommandFrame = -999
    private var lastWeather = "неизвестно"
    private var lastTx = 0.5f
    private var lastTy = 0.82f
    private var lastRoadWidth = 0f
    private var lastFreeSector = 0f
    private var lastBrightness = 128f

    private val priorHeightM = mapOf(
        "person" to 1.70f,
        "car" to 1.50f,
        "building" to 8.0f,
        "obstacle" to 0.90f,
        "motion" to 1.00f
    )

    fun process(bitmap: Bitmap): FrameResult {
        frameIndex++
        val w = bitmap.width
        val h = bitmap.height
        val scale = 340f / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(96)
        val sh = (h * scale).toInt().coerceAtLeast(72)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val luma = IntArray(sw * sh)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        var sumL = 0L
        for (i in pixels.indices) {
            val c = pixels[i]
            val v = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
            luma[i] = v
            sumL += v
        }
        val brightness = (sumL / luma.size).toFloat()
        lastBrightness = 0.7f * lastBrightness + 0.3f * brightness
        val enhanced = if (lastBrightness < 70f) enhanceLowLight(luma, sw, sh) else luma

        val detections = mutableListOf<Detection>()
        val commands = mutableListOf<NavigationCommand>()
        detections.addAll(detectAndClassify(enhanced, sw, sh, lastBrightness))

        val motionRects = detectStrongMotion(enhanced, sw, sh, lastBrightness)
        if (motionRects.isNotEmpty()) {
            motionHits = (motionHits + 1).coerceAtMost(14)
            for (r in motionRects) {
                val cls = classifyBox(r, sw, sh)
                detections.add(
                    Detection(
                        if (cls == "obstacle") "motion" else cls,
                        r, 0.8f, estimateDistance(r, sh, cls), sideOf(r, sw)
                    )
                )
            }
        } else {
            motionHits = (motionHits - 2).coerceAtLeast(0)
        }

        val merged = mergeOverlapping(detections).sortedBy { it.distanceM.takeIf { d -> d > 0 } ?: 99f }
        val nearest = merged.filter { it.distanceM > 0 }.minByOrNull { it.distanceM }
        val nearestDist = nearest?.distanceM ?: -1f
        val proximityScore = computeProximityScore(merged, sw, sh)

        val (freeDeg, nextPt) = nearestNeighborFreePath(merged, sw, sh)
        lastFreeSector = 0.6f * lastFreeSector + 0.4f * freeDeg
        val bypass = when {
            freeDeg < -18f -> "объезд влево (${freeDeg.toInt()}°)"
            freeDeg > 18f -> "объезд вправо (+${freeDeg.toInt()}°)"
            nearestDist in 0.1f..1.5f -> "осторожно, близко"
            merged.isEmpty() -> "путь свободен"
            else -> "держите курс"
        }

        val closeByDistance = nearestDist > 0f && nearestDist <= 1.8f
        val closeBySize = proximityScore >= 0.55f
        val motionDanger = motionHits >= 5
        val wantStop = forceStop || (strictStopEnabled && (closeByDistance || closeBySize || motionDanger))

        if (wantStop) {
            stopActive = true
            stopHoldFrames = 12
        } else if (stopHoldFrames > 0) {
            stopHoldFrames--
        } else if (!forceStop) {
            stopActive = false
        }
        val stopSignal = stopActive || forceStop

        val (txRaw, tyRaw, roadWidth) = estimateRoadTarget(enhanced, sw, sh)
        val biasX = (freeDeg / 90f) * 0.22f
        lastTx = (0.55f * lastTx + 0.45f * (txRaw + biasX).coerceIn(0.12f, 0.88f))
        lastTy = 0.7f * lastTy + 0.3f * tyRaw
        lastRoadWidth = 0.85f * lastRoadWidth + 0.15f * roadWidth

        val displacement = estimateDisplacement(enhanced, sw, sh)
        val rawSpeed = (displacement * 0.04f * 10f).coerceIn(0f, 10f)
        smoothedSpeed = 0.25f * rawSpeed + 0.75f * smoothedSpeed
        if (!stopSignal) totalDistance += smoothedSpeed / 10f

        if (frameIndex % 18 == 0) lastWeather = estimateWeather(pixels, sw, sh, lastBrightness)

        if (frameIndex - lastCommandFrame >= 7) {
            lastCommandFrame = frameIndex
            if (stopSignal) {
                commands.add(
                    NavigationCommand(
                        if (forceStop) "СТОП (кнопка)" else "СТОП — препятствие близко",
                        NavigationCommand.Severity.STOP
                    )
                )
            } else {
                val dir = when {
                    lastTx < 0.36f -> "влево"
                    lastTx > 0.64f -> "вправо"
                    else -> "прямо"
                }
                commands.add(NavigationCommand("NN курс: $dir (сектор ${freeDeg.toInt()}°)", NavigationCommand.Severity.NAV))
            }
            if (nearestDist > 0 && nearest != null) {
                commands.add(
                    NavigationCommand(
                        "${nearest.type} ≈ " + String.format("%.1f", nearestDist) + " м (" + nearest.side + ")",
                        NavigationCommand.Severity.WARN
                    )
                )
            }
            if (lastBrightness < 55f) {
                commands.add(NavigationCommand("Темно — включите вспышку", NavigationCommand.Severity.WARN))
            }
            commands.add(NavigationCommand("Обход: $bypass", NavigationCommand.Severity.NAV))
            if (merged.isNotEmpty()) {
                commands.add(NavigationCommand("Объектов: ${merged.size}", NavigationCommand.Severity.INFO))
            }
            commands.add(
                NavigationCommand(
                    "Скорость " + String.format("%.1f", smoothedSpeed) +
                        " м/с | Путь " + String.format("%.1f", totalDistance) + " м",
                    NavigationCommand.Severity.INFO
                )
            )
            for (c in commands) {
                commandHistory.addFirst(c)
                if (commandHistory.size > 20) commandHistory.removeLast()
            }
        }

        prevLuma = enhanced
        val mapped = merged.map {
            Detection(
                it.type,
                RectF(it.rect.left / scale, it.rect.top / scale, it.rect.right / scale, it.rect.bottom / scale),
                it.confidence, it.distanceM, it.side
            )
        }
        return FrameResult(
            mapped, stopSignal, lastTx, lastTy, smoothedSpeed, totalDistance.toFloat(),
            lastWeather, lastRoadWidth, commandHistory.toList(), nearestDist, bypass,
            lastFreeSector, Pair(nextPt.first, nextPt.second), lastBrightness
        )
    }

    private fun computeProximityScore(dets: List<Detection>, w: Int, h: Int): Float {
        if (dets.isEmpty()) return 0f
        val lowerTop = h * 0.35f
        var best = 0f
        for (d in dets) {
            val rh = d.rect.height() / h
            val rw = d.rect.width() / w
            val cy = d.rect.centerY()
            if (cy < lowerTop) continue
            val area = (rh * rw).coerceIn(0f, 1f)
            val distBoost = when {
                d.distanceM in 0.1f..1.2f -> 0.35f
                d.distanceM in 1.2f..2.0f -> 0.15f
                else -> 0f
            }
            val score = (area * 1.6f + rh * 0.9f + distBoost).coerceIn(0f, 1f)
            if (score > best) best = score
        }
        return best
    }

    private fun enhanceLowLight(luma: IntArray, w: Int, h: Int): IntArray {
        var minV = 255; var maxV = 0
        for (v in luma) { if (v < minV) minV = v; if (v > maxV) maxV = v }
        val range = (maxV - minV).coerceAtLeast(1)
        val out = IntArray(luma.size)
        for (i in luma.indices) {
            val n = (luma[i] - minV).toFloat() / range
            out[i] = (n.pow(0.7f) * 255f).toInt().coerceIn(0, 255)
        }
        return out
    }

    private fun detectAndClassify(luma: IntArray, w: Int, h: Int, brightness: Float): List<Detection> {
        val edgeThresh = when {
            brightness < 40f -> 28
            brightness < 70f -> 40
            brightness < 110f -> 52
            else -> 62
        }
        val edge = BooleanArray(luma.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = abs(luma[y * w + x + 1] - luma[y * w + x - 1])
            val gy = abs(luma[(y + 1) * w + x] - luma[(y - 1) * w + x])
            edge[y * w + x] = (gx + gy) > edgeThresh
        }
        val dil = BooleanArray(luma.size)
        val dilR = if (brightness < 60f) 2 else 1
        for (y in dilR until h - dilR) for (x in dilR until w - dilR) {
            if (edge[y * w + x]) for (dy in -dilR..dilR) for (dx in -dilR..dilR) dil[(y + dy) * w + (x + dx)] = true
        }
        val visited = BooleanArray(luma.size)
        val results = mutableListOf<Detection>()
        val minArea = (w * h * 0.0045).toInt().coerceAtLeast(45)
        for (y in (h * 0.08f).toInt() until h step 2) {
            for (x in 1 until w - 1 step 2) {
                val idx = y * w + x
                if (!dil[idx] || visited[idx]) continue
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val q = ArrayDeque<Int>(); q.add(idx); visited[idx] = true
                var area = 0
                while (q.isNotEmpty() && area < 20000) {
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
                if (area >= minArea && bw > 8 && bh > 8) {
                    val padX = max(4, bw / 10); val padY = max(4, bh / 12)
                    val rect = RectF(
                        (minX - padX).toFloat().coerceAtLeast(0f),
                        (minY - padY).toFloat().coerceAtLeast(0f),
                        (maxX + padX).toFloat().coerceAtMost(w - 1f),
                        (maxY + padY).toFloat().coerceAtMost(h - 1f)
                    )
                    val cls = classifyBox(rect, w, h)
                    val conf = when (cls) { "person" -> 0.8f; "car" -> 0.78f; "building" -> 0.72f; else -> 0.65f }
                    results.add(Detection(cls, rect, conf, estimateDistance(rect, h, cls), sideOf(rect, w)))
                }
            }
        }
        return mergeOverlapping(results).take(12)
    }

    private fun classifyBox(rect: RectF, frameW: Int, frameH: Int): String {
        val bw = rect.width().coerceAtLeast(1f)
        val bh = rect.height().coerceAtLeast(1f)
        val aspect = bw / bh
        val cy = rect.centerY() / frameH
        val areaRatio = (bw * bh) / (frameW * frameH)
        return when {
            aspect < 0.62f && bh > frameH * 0.10f && cy > 0.18f && areaRatio < 0.35f -> "person"
            aspect > 1.15f && aspect < 3.8f && cy > 0.30f && areaRatio > 0.015f && areaRatio < 0.45f -> "car"
            bh > frameH * 0.28f && cy < 0.55f && areaRatio > 0.06f && aspect < 1.8f -> "building"
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
        val maxZ = if (type == "building") 80f else 40f
        return (z * groundBias).coerceIn(0.25f, maxZ)
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
            if (d.type == "building" && d.distanceM > 5f) continue
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

    private fun detectStrongMotion(luma: IntArray, w: Int, h: Int, brightness: Float): List<RectF> {
        val prev = prevLuma ?: return emptyList()
        if (prev.size != luma.size) return emptyList()
        val thresh = when { brightness < 45f -> 22; brightness < 80f -> 32; else -> 45 }
        var changed = 0
        val binary = BooleanArray(luma.size)
        val y0 = (h * 0.25f).toInt()
        for (y in y0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (abs(luma[i] - prev[i]) > thresh) { binary[i] = true; changed++ }
        }
        val region = (h - y0) * w
        if (changed < 70 || changed > region * 0.28) return emptyList()
        val visited = BooleanArray(luma.size)
        val rects = mutableListOf<RectF>()
        for (y in y0 until h step 4) for (x in 2 until w - 2 step 4) {
            val idx = y * w + x
            if (!binary[idx] || visited[idx]) continue
            var minX = x; var maxX = x; var minY = y; var maxY = y
            val q = ArrayDeque<Int>(); q.add(idx); visited[idx] = true
            var area = 0
            while (q.isNotEmpty() && area < 7000) {
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
            if (area >= 550 && (maxX - minX) > 10 && (maxY - minY) > 10)
                rects.add(RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()))
        }
        return rects.take(6)
    }

    private fun estimateRoadTarget(luma: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val roiY = (h * 0.58f).toInt()
        if (h - roiY < 8) return Triple(0.5f, 0.82f, 0f)
        val edgeXs = mutableListOf<Int>()
        for (y in roiY until h step 3) for (x in 4 until w - 4) {
            if (abs(luma[y * w + x + 1] - luma[y * w + x - 1]) > 38) edgeXs.add(x)
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

    private fun estimateWeather(pixels: IntArray, w: Int, h: Int, brightness: Float): String {
        if (brightness < 40f) return "темно / ночь"
        val skyEnd = (h * 0.28f).toInt()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in 0 until skyEnd step 3) for (x in 0 until w step 3) {
            val c = pixels[y * w + x]
            rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF; n++
        }
        if (n == 0) return "неизвестно"
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
        commandHistory.clear(); motionHits = 0; stopActive = false; stopHoldFrames = 0
        forceStop = false; lastCommandFrame = -999
    }
}
