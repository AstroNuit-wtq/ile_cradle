package com.astronuit.app.data

import androidx.compose.ui.graphics.Color
import kotlin.math.*

data class BrightStar(
    val name: String,
    val ra: Double,
    val dec: Double,
    val mag: Double,
    val spectral: String
) {
    val x: Float get() {
        val raRad = ra * PI / 12
        val decRad = dec * PI / 180
        return (cos(decRad) * cos(raRad)).toFloat()
    }
    val y: Float get() {
        val decRad = dec * PI / 180
        return sin(decRad).toFloat()
    }
    val color: Color get() = when (spectral.firstOrNull()) {
        'O', 'B' -> Color(0.7f, 0.8f, 1f)
        'A' -> Color(0.9f, 0.95f, 1f)
        'F' -> Color.White
        'G' -> Color(1f, 0.98f, 0.85f)
        'K' -> Color(1f, 0.85f, 0.6f)
        'M' -> Color(1f, 0.65f, 0.45f)
        else -> Color.White
    }
}

object BrightStarCatalog {
    val stars = listOf(
        BrightStar("Sirius", 6.7525, -16.7161, -1.46, "A1"),
        BrightStar("Canopus", 6.3992, -52.6956, -0.74, "A9"),
        BrightStar("Arcturus", 14.2610, 19.1825, -0.05, "K1.5"),
        BrightStar("Vega", 18.6156, 38.7836, 0.03, "A0"),
        BrightStar("Capella", 5.2782, 45.9980, 0.08, "G5"),
        BrightStar("Rigel", 5.2423, -8.2017, 0.13, "B8"),
        BrightStar("Procyon", 7.6550, 5.2250, 0.34, "F5"),
        BrightStar("Betelgeuse", 5.9195, 7.4070, 0.42, "M2"),
        BrightStar("Altair", 19.8463, 8.8683, 0.76, "A7"),
        BrightStar("Aldebaran", 4.5987, 16.5092, 0.86, "K5"),
        BrightStar("Antares", 16.4901, -26.4319, 0.96, "M1"),
        BrightStar("Spica", 13.4199, -11.1613, 0.98, "B1"),
        BrightStar("Pollux", 7.7553, 28.0262, 1.14, "K0"),
        BrightStar("Fomalhaut", 22.9608, -29.6222, 1.16, "A3"),
        BrightStar("Deneb", 20.6905, 45.2803, 1.25, "A2"),
        BrightStar("Regulus", 10.1395, 11.9672, 1.35, "B7"),
        BrightStar("Bellatrix", 5.4189, 6.3497, 1.64, "B2"),
        BrightStar("Alnilam", 5.6036, -1.2019, 1.69, "B0"),
        BrightStar("Alioth", 12.9005, 55.9598, 1.77, "A1"),
        BrightStar("Dubhe", 11.0621, 61.7510, 1.79, "K0"),
        BrightStar("Alkaid", 13.7924, 49.3133, 1.86, "B3"),
        BrightStar("Polaris", 2.5303, 89.2641, 1.98, "F7"),
        BrightStar("Gacrux", 12.5194, -57.1133, 1.63, "M3"),
        BrightStar("Hamal", 2.1195, 23.4625, 2.00, "K2"),
        BrightStar("Denebola", 11.8177, 14.5721, 2.14, "A3")
    )
}

data class Planet(
    val name: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Color
)

object PlanetCatalog {
    // Approximate visual positions for demo (unit sphere projection)
    val all = listOf(
        Planet("Солнце", 0.3f, 0.1f, 18f, Color(1f, 0.95f, 0.6f)),
        Planet("Луна", -0.2f, 0.25f, 12f, Color(0.9f, 0.9f, 0.85f)),
        Planet("Меркурий", 0.15f, -0.2f, 6f, Color(0.8f, 0.8f, 0.8f)),
        Planet("Венера", -0.35f, 0.05f, 8f, Color(1f, 0.9f, 0.7f)),
        Planet("Марс", 0.4f, -0.3f, 7f, Color(1f, 0.4f, 0.2f)),
        Planet("Юпитер", -0.5f, -0.15f, 11f, Color(0.95f, 0.8f, 0.6f)),
        Planet("Сатурн", 0.55f, 0.2f, 10f, Color(0.95f, 0.85f, 0.55f)),
        Planet("Уран", -0.1f, 0.45f, 7f, Color(0.6f, 0.9f, 0.95f)),
        Planet("Нептун", 0.25f, -0.4f, 7f, Color(0.3f, 0.5f, 1f))
    )
}

data class Nebula(
    val name: String,
    val type: String,
    val description: String,
    val imageUrl: String,
    val credit: String
)

object NebulaCatalog {
    val all = listOf(
        Nebula(
            "Туманность Ориона (M42)",
            "Эмиссионная",
            "Одна из самых ярких туманностей. Область активного звездообразования.",
            "https://cdn.spacetelescope.org/archives/images/large/heic0601a.jpg",
            "NASA, ESA"
        ),
        Nebula(
            "Крабовидная туманность (M1)",
            "Остаток сверхновой",
            "Остаток сверхновой 1054 года. В центре — пульсар.",
            "https://cdn.spacetelescope.org/archives/images/large/heic0515a.jpg",
            "NASA, ESA"
        ),
        Nebula(
            "Кольцевая туманность (M57)",
            "Планетарная",
            "Классическая планетарная туманность в созвездии Лиры.",
            "https://cdn.spacetelescope.org/archives/images/large/heic1310a.jpg",
            "NASA, ESA"
        ),
        Nebula(
            "Столпы Творения (M16)",
            "Эмиссионная",
            "Знаменитые колонны газа и пыли в туманности Орла.",
            "https://cdn.spacetelescope.org/archives/images/large/heic1501a.jpg",
            "NASA, ESA"
        )
    )
}
