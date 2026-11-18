package com.astronuit.app.data

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val DEG_TO_RAD = PI / 180.0
private const val RAD_TO_DEG = 180.0 / PI

/** Horizontal coordinates for an observer: azimuth is measured clockwise from true north. */
data class HorizontalPosition(
    val azimuthDeg: Double,
    val altitudeDeg: Double
)

/**
 * Converts the catalogue J2000 equatorial coordinates to the observer's horizon.
 * The precession step accounts for the change of equinox; the sidereal-time step
 * accounts for UTC time and observer longitude.
 */
object SkyCalculator {
    fun horizontalPosition(
        star: BrightStar,
        latitudeDeg: Double,
        longitudeDeg: Double,
        instant: Instant
    ): HorizontalPosition {
        val jd = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5
        val centuries = (jd - 2_451_545.0) / 36_525.0

        val zeta = arcSecondsToRadians(
            2306.2181 * centuries + 0.30188 * centuries * centuries + 0.017998 * centuries * centuries * centuries
        )
        val z = arcSecondsToRadians(
            2306.2181 * centuries + 1.09468 * centuries * centuries + 0.018203 * centuries * centuries * centuries
        )
        val theta = arcSecondsToRadians(
            2004.3109 * centuries - 0.42665 * centuries * centuries - 0.041833 * centuries * centuries * centuries
        )

        val raJ2000 = star.ra * 15.0 * DEG_TO_RAD
        val decJ2000 = star.dec * DEG_TO_RAD
        val a = cos(decJ2000) * sin(raJ2000 + zeta)
        val b = cos(theta) * cos(decJ2000) * cos(raJ2000 + zeta) - sin(theta) * sin(decJ2000)
        val c = sin(theta) * cos(decJ2000) * cos(raJ2000 + zeta) + cos(theta) * sin(decJ2000)
        val raDate = normalizeRadians(atan2(a, b) + z)
        val decDate = asin(c.coerceIn(-1.0, 1.0))

        val daysSinceJ2000 = jd - 2_451_545.0
        val gmst = normalizeDegrees(
            280.46061837 + 360.98564736629 * daysSinceJ2000 +
                0.000387933 * centuries * centuries -
                centuries * centuries * centuries / 38_710_000.0
        )
        val localSidereal = normalizeDegrees(gmst + longitudeDeg) * DEG_TO_RAD
        val hourAngle = normalizeSignedRadians(localSidereal - raDate)
        val latitude = latitudeDeg * DEG_TO_RAD

        val sinAltitude = sin(decDate) * sin(latitude) +
            cos(decDate) * cos(latitude) * cos(hourAngle)
        val altitude = asin(sinAltitude.coerceIn(-1.0, 1.0))
        val azimuth = atan2(
            -sin(hourAngle) * cos(decDate),
            sin(decDate) * cos(latitude) - cos(decDate) * sin(latitude) * cos(hourAngle)
        )

        return HorizontalPosition(
            azimuthDeg = normalizeDegrees(azimuth * RAD_TO_DEG),
            altitudeDeg = altitude * RAD_TO_DEG
        )
    }

    private fun arcSecondsToRadians(value: Double) = value / 3600.0 * DEG_TO_RAD
    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeRadians(value: Double): Double = ((value % (2.0 * PI)) + 2.0 * PI) % (2.0 * PI)
    private fun normalizeSignedRadians(value: Double): Double {
        var normalized = normalizeRadians(value)
        if (normalized > PI) normalized -= 2.0 * PI
        return normalized
    }
}
