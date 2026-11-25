package com.diplomka.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import com.diplomka.app.vision.DroneVisionPipeline

@Composable
fun DetectionOverlay(
    detections: List<DroneVisionPipeline.Detection>,
    targetX: Float,
    targetY: Float,
    stopSignal: Boolean,
    nnPoint: Pair<Float, Float>,
    autoRotateAngle: Float,
    showCompass: Boolean,
    screenWaypoint: Offset?,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "arrow")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val bob by infinite.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val textPaint = android.graphics.Paint().apply {
            textSize = 28f
            isAntiAlias = true
            isFakeBoldText = true
        }

        for (d in detections) {
            val left = d.rect.left / d.rect.width().coerceAtLeast(1f)
            // rect is already in pixel space relative to analysis frame; map roughly to canvas
            val l = d.rect.left
            val t = d.rect.top
            val r = d.rect.right
            val b = d.rect.bottom
            // If coords look normalized (0..1), scale them
            val leftPx: Float
            val topPx: Float
            val rightPx: Float
            val bottomPx: Float
            if (r <= 1.5f && b <= 1.5f) {
                leftPx = l * w; topPx = t * h; rightPx = r * w; bottomPx = b * h
            } else {
                leftPx = l; topPx = t; rightPx = r; bottomPx = b
            }
            val color = when (d.type) {
                "person" -> Color(0xFFFF5252)
                "car" -> Color(0xFFFFAB40)
                "building" -> Color(0xFF82B1FF)
                "fence" -> Color(0xFFB388FF)
                "statue" -> Color(0xFF69F0AE)
                else -> Color(0xFFFFF176)
            }
            drawRect(color.copy(alpha = 0.15f), Offset(leftPx, topPx), Size((rightPx - leftPx).coerceAtLeast(1f), (bottomPx - topPx).coerceAtLeast(1f)))
            drawRect(color, Offset(leftPx, topPx), Size((rightPx - leftPx).coerceAtLeast(1f), (bottomPx - topPx).coerceAtLeast(1f)), style = Stroke(width = 3f))
            val label = buildString {
                append(d.type)
                if (d.distanceM > 0) append(" ").append(String.format("%.1f", d.distanceM)).append("м")
            }
            val ty = (topPx - 8f).coerceAtLeast(28f)
            textPaint.color = android.graphics.Color.BLACK
            drawContext.canvas.nativeCanvas.drawText(label, leftPx + 3f, ty, textPaint)
            textPaint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(label, leftPx + 2f, ty - 1f, textPaint)
        }

        val nx = nnPoint.first * w
        val ny = nnPoint.second * h
        drawCircle(Color(0xFF69F0AE), 10f, Offset(nx, ny))
        drawLine(Color(0xFF69F0AE), Offset(w / 2f, h * 0.9f), Offset(nx, ny), 3f)
        drawCircle(Color(0xFF00E676), 7f, Offset(targetX * w, targetY * h))

        screenWaypoint?.let { wp ->
            val px = wp.x * w
            val py = wp.y * h
            drawCircle(Color(0xFFFF5252), 14f, Offset(px, py))
            drawCircle(Color(0x88FF5252), 28f, Offset(px, py), style = Stroke(3f))
            drawLine(Color(0xAAFF5252), Offset(w / 2f, h), Offset(px, py), 2.5f)
        }

        if (showCompass) {
            val cx = w / 2f
            val cy = h * 0.78f + bob
            rotate(degrees = autoRotateAngle, pivot = Offset(cx, cy)) {
                val s = 1.35f * pulse
                val shadow = Path().apply {
                    moveTo(cx + 3f, cy - 52f * s + 4f)
                    lineTo(cx - 20f * s + 3f, cy + 26f * s + 4f)
                    lineTo(cx + 3f, cy + 8f * s + 4f)
                    lineTo(cx + 20f * s + 3f, cy + 26f * s + 4f)
                    close()
                }
                drawPath(shadow, Color(0x66000000))
                val body = Path().apply {
                    moveTo(cx, cy - 52f * s)
                    lineTo(cx - 20f * s, cy + 26f * s)
                    lineTo(cx, cy + 8f * s)
                    lineTo(cx + 20f * s, cy + 26f * s)
                    close()
                }
                drawPath(body, Color(0xFFFFD740))
                val highlight = Path().apply {
                    moveTo(cx, cy - 52f * s)
                    lineTo(cx - 8f * s, cy + 2f * s)
                    lineTo(cx, cy + 8f * s)
                    close()
                }
                drawPath(highlight, Color(0xFFFFF59D))
            }
            drawCircle(Color(0x55FFD740), 48f * pulse, Offset(cx, cy), style = Stroke(2f))
        }
        if (stopSignal) drawRect(Color(0x44FF0000), Offset.Zero, Size(w, h))
    }
}
