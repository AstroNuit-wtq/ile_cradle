package com.astronuit.app.data

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class BrightStar(
    val name: String,
    val nameRu: String,
    val ra: Double,
    val dec: Double,
    val mag: Double,
    val spectral: String,
    val constellation: String,
    val constellationRu: String
) {
    val dirX: Float get() = (cos(dec * PI / 180.0) * cos(ra * PI / 12.0)).toFloat()
    val dirY: Float get() = sin(dec * PI / 180.0).toFloat()
    val dirZ: Float get() = (cos(dec * PI / 180.0) * sin(ra * PI / 12.0)).toFloat()
    val color: Color get() = when (spectral.firstOrNull()) {
        'O', 'B' -> Color(0.65f, 0.75f, 1f)
        'A' -> Color(0.85f, 0.9f, 1f)
        'F' -> Color(1f, 1f, 0.95f)
        'G' -> Color(1f, 0.95f, 0.75f)
        'K' -> Color(1f, 0.8f, 0.55f)
        'M' -> Color(1f, 0.55f, 0.4f)
        else -> Color.White
    }
}

object BrightStarCatalog {
    val stars = listOf(
        BrightStar("Betelgeuse", "Бетельгейзе", 5.9195, 7.4070, 0.42, "M2", "Ori", "Орион"),
        BrightStar("Rigel", "Ригель", 5.2423, -8.2017, 0.13, "B8", "Ori", "Орион"),
        BrightStar("Bellatrix", "Беллатрикс", 5.4189, 6.3497, 1.64, "B2", "Ori", "Орион"),
        BrightStar("Alnilam", "Альнилам", 5.6036, -1.2019, 1.69, "B0", "Ori", "Орион"),
        BrightStar("Alnitak", "Альнитак", 5.6793, -1.9426, 1.77, "O9", "Ori", "Орион"),
        BrightStar("Saiph", "Саиф", 5.7959, -9.6699, 2.09, "B0", "Ori", "Орион"),
        BrightStar("Mintaka", "Минтака", 5.5334, -0.2991, 2.23, "O9", "Ori", "Орион"),
        BrightStar("Sirius", "Сириус", 6.7525, -16.7161, -1.46, "A1", "CMa", "Большой Пёс"),
        BrightStar("Adhara", "Адара", 6.9771, -28.9721, 1.50, "B2", "CMa", "Большой Пёс"),
        BrightStar("Wezen", "Везен", 7.1395, -26.3932, 1.83, "F8", "CMa", "Большой Пёс"),
        BrightStar("Mirzam", "Мирцам", 6.3783, -17.9559, 1.98, "B1", "CMa", "Большой Пёс"),
        BrightStar("Aludra", "Альудра", 7.4016, -29.3031, 2.45, "B5", "CMa", "Большой Пёс"),
        BrightStar("Vega", "Вега", 18.6156, 38.7836, 0.03, "A0", "Lyr", "Лира"),
        BrightStar("Deneb", "Денеб", 20.6905, 45.2803, 1.25, "A2", "Cyg", "Лебедь"),
        BrightStar("Altair", "Альтаир", 19.8463, 8.8683, 0.76, "A7", "Aql", "Орёл"),
        BrightStar("Aldebaran", "Альдебаран", 4.5987, 16.5092, 0.86, "K5", "Tau", "Телец"),
        BrightStar("Elnath", "Эль-Нат", 5.4382, 28.6075, 1.65, "B7", "Tau", "Телец"),
        BrightStar("Antares", "Антарес", 16.4901, -26.4319, 0.96, "M1", "Sco", "Скорпион"),
        BrightStar("Spica", "Спика", 13.4199, -11.1613, 0.98, "B1", "Vir", "Дева"),
        BrightStar("Regulus", "Регул", 10.1395, 11.9672, 1.35, "B7", "Leo", "Лев"),
        BrightStar("Denebola", "Денебола", 11.8177, 14.5721, 2.14, "A3", "Leo", "Лев"),
        BrightStar("Pollux", "Поллукс", 7.7553, 28.0262, 1.14, "K0", "Gem", "Близнецы"),
        BrightStar("Castor", "Кастор", 7.5766, 31.8885, 1.58, "A1", "Gem", "Близнецы"),
        BrightStar("Alhena", "Альгена", 6.6286, 16.3993, 1.93, "A0", "Gem", "Близнецы"),
        BrightStar("Dubhe", "Дубхе", 11.0621, 61.7510, 1.79, "K0", "UMa", "Большая Медведица"),
        BrightStar("Merak", "Мерак", 11.0307, 56.3824, 2.37, "A1", "UMa", "Большая Медведица"),
        BrightStar("Phecda", "Фекда", 11.8972, 53.6948, 2.44, "A0", "UMa", "Большая Медведица"),
        BrightStar("Megrez", "Мегрец", 12.2571, 57.0326, 3.31, "A3", "UMa", "Большая Медведица"),
        BrightStar("Alioth", "Алиот", 12.9005, 55.9598, 1.77, "A1", "UMa", "Большая Медведица"),
        BrightStar("Mizar", "Мицар", 13.3987, 54.9254, 2.27, "A2", "UMa", "Большая Медведица"),
        BrightStar("Alkaid", "Бенетнаш", 13.7924, 49.3133, 1.86, "B3", "UMa", "Большая Медведица"),
        BrightStar("Polaris", "Полярная", 2.5303, 89.2641, 1.98, "F7", "UMi", "Малая Медведица"),
        BrightStar("Kochab", "Кохаб", 14.8451, 74.1555, 2.08, "K4", "UMi", "Малая Медведица"),
        BrightStar("Arcturus", "Арктур", 14.2610, 19.1825, -0.05, "K1.5", "Boo", "Волопас"),
        BrightStar("Capella", "Капелла", 5.2782, 45.9980, 0.08, "G5", "Aur", "Возничий"),
        BrightStar("Procyon", "Процион", 7.6550, 5.2250, 0.34, "F5", "CMi", "Малый Пёс"),
        BrightStar("Canopus", "Канопус", 6.3992, -52.6956, -0.74, "A9", "Car", "Киль"),
        BrightStar("Achernar", "Ахернар", 1.6286, -57.2367, 0.46, "B6", "Eri", "Эридан"),
        BrightStar("Fomalhaut", "Фомальгаут", 22.9608, -29.6222, 1.16, "A3", "PsA", "Южная Рыба"),
        BrightStar("Gacrux", "Гакрукс", 12.5194, -57.1133, 1.63, "M3", "Cru", "Южный Крест"),
        BrightStar("Acrux", "Акрукс", 12.4433, -63.0991, 0.76, "B0", "Cru", "Южный Крест"),
        BrightStar("Mimosa", "Мимоза", 12.7954, -59.6888, 1.25, "B0", "Cru", "Южный Крест"),
        BrightStar("Hamal", "Хамаль", 2.1195, 23.4625, 2.00, "K2", "Ari", "Овен"),
        BrightStar("Alpheratz", "Альферац", 0.1398, 29.0906, 2.06, "B9", "And", "Андромеда"),
        BrightStar("Mirach", "Мирах", 1.1622, 35.6206, 2.06, "M0", "And", "Андромеда"),
        BrightStar("Algol", "Алголь", 3.1361, 40.9556, 2.12, "B8", "Per", "Персей"),
        BrightStar("Mirfak", "Мирфак", 3.4054, 49.8612, 1.79, "F5", "Per", "Персей"),
        BrightStar("Alphard", "Альфард", 9.4597, -8.6586, 1.99, "K3", "Hya", "Гидра"),
        BrightStar("Sheliak", "Шелиак", 18.8347, 33.3627, 3.52, "B8", "Lyr", "Лира"),
        BrightStar("Sulafat", "Сулафат", 18.9824, 32.6896, 3.25, "B9", "Lyr", "Лира"),
        BrightStar("Sadr", "Садр", 20.3705, 40.2567, 2.23, "F8", "Cyg", "Лебедь"),
        BrightStar("Gienah", "Дженах", 20.7702, 33.9703, 2.46, "K0", "Cyg", "Лебедь"),
        BrightStar("Albireo", "Альбирео", 19.5120, 27.9597, 3.08, "K3", "Cyg", "Лебедь"),
        BrightStar("Fawaris", "Фаварис", 19.7496, 45.1308, 2.87, "A0", "Cyg", "Лебедь"),
        BrightStar("Tarazed", "Таразед", 19.7710, 10.6133, 2.72, "K3", "Aql", "Орёл"),
        BrightStar("Alshain", "Альшаин", 19.9210, 6.4067, 3.71, "G8", "Aql", "Орёл"),
        BrightStar("Dschubba", "Джубба", 16.0056, -22.6217, 2.32, "B0", "Sco", "Скорпион"),
        BrightStar("Acrab", "Акраб", 16.0906, -19.8054, 2.62, "B1", "Sco", "Скорпион"),
        BrightStar("Shaula", "Шаула", 17.5601, -37.1038, 1.62, "B2", "Sco", "Скорпион"),
        BrightStar("Sargas", "Саргас", 17.6219, -42.9978, 1.86, "F1", "Sco", "Скорпион"),
        BrightStar("Lesath", "Лесат", 17.5127, -37.2958, 2.70, "B2", "Sco", "Скорпион"),
        BrightStar("Porrima", "Поррима", 12.6943, -1.4494, 2.74, "F0", "Vir", "Дева"),
        BrightStar("Vindemiatrix", "Виндемиатрикс", 13.0363, 10.9591, 2.83, "G8", "Vir", "Дева"),
        BrightStar("Algieba", "Альгиеба", 10.3329, 19.8415, 2.01, "K1", "Leo", "Лев"),
        BrightStar("Zosma", "Зосма", 11.2351, 20.5237, 2.56, "A4", "Leo", "Лев"),
        BrightStar("Chertan", "Хорт", 11.2373, 15.4296, 3.33, "A3", "Leo", "Лев"),
        BrightStar("Pherkad", "Феркард", 15.3455, 71.8339, 3.05, "A3", "UMi", "Малая Медведица"),
        BrightStar("Izar", "Изар", 14.7498, 27.0742, 2.35, "K0", "Boo", "Волопас"),
        BrightStar("Muphrid", "Муфрид", 13.9114, 18.3977, 2.68, "G0", "Boo", "Волопас"),
        BrightStar("Nekkar", "Неккар", 15.0324, 40.3906, 3.50, "G8", "Boo", "Волопас"),
        BrightStar("Menkalinan", "Менкалинан", 5.9923, 44.9474, 1.90, "A2", "Aur", "Возничий"),
        BrightStar("Mahasim", "Махасим", 5.9921, 44.9474, 2.62, "A1", "Aur", "Возничий"),
        BrightStar("Hassaleh", "Хассалех", 4.9499, 33.1662, 2.69, "A0", "Aur", "Возничий"),
        BrightStar("Gomeisa", "Гомейса", 7.4525, 8.2892, 2.89, "B8", "CMi", "Малый Пёс"),
        BrightStar("Avior", "Авиор", 8.3752, -59.5095, 1.86, "K3", "Car", "Киль"),
        BrightStar("Aspidiske", "Аспидиске", 9.2200, -59.2753, 2.21, "A8", "Car", "Киль"),
        BrightStar("Caph", "Каф", 0.1529, 59.1498, 2.28, "F2", "Cas", "Кассиопея"),
        BrightStar("Schedar", "Шедар", 0.6751, 56.5373, 2.24, "K0", "Cas", "Кассиопея"),
        BrightStar("Navi", "Нави", 0.9451, 60.7167, 2.47, "B0", "Cas", "Кассиопея"),
        BrightStar("Ruchbah", "Рукба", 1.4302, 60.2353, 2.68, "A5", "Cas", "Кассиопея"),
        BrightStar("Segin", "Сегин", 1.9066, 63.6701, 3.37, "B3", "Cas", "Кассиопея")
    )
}

/** Named star pairs that form simplified constellation stick figures. */
object ConstellationLines {
    val byCode: Map<String, List<Pair<String, String>>> = mapOf(
        "Ori" to listOf(
            "Betelgeuse" to "Bellatrix", "Bellatrix" to "Mintaka", "Mintaka" to "Alnilam",
            "Alnilam" to "Alnitak", "Alnitak" to "Saiph", "Saiph" to "Rigel",
            "Rigel" to "Mintaka", "Betelgeuse" to "Alnitak"
        ),
        "UMa" to listOf(
            "Dubhe" to "Merak", "Merak" to "Phecda", "Phecda" to "Megrez",
            "Megrez" to "Alioth", "Alioth" to "Mizar", "Mizar" to "Alkaid", "Megrez" to "Dubhe"
        ),
        "UMi" to listOf("Polaris" to "Kochab", "Kochab" to "Pherkad", "Pherkad" to "Polaris"),
        "Cyg" to listOf(
            "Deneb" to "Sadr", "Sadr" to "Gienah", "Sadr" to "Fawaris", "Sadr" to "Albireo"
        ),
        "Lyr" to listOf("Vega" to "Sheliak", "Sheliak" to "Sulafat", "Sulafat" to "Vega"),
        "Aql" to listOf("Altair" to "Tarazed", "Altair" to "Alshain"),
        "Tau" to listOf("Aldebaran" to "Elnath"),
        "Sco" to listOf(
            "Dschubba" to "Acrab", "Acrab" to "Antares", "Antares" to "Shaula",
            "Shaula" to "Lesath", "Shaula" to "Sargas"
        ),
        "Leo" to listOf(
            "Regulus" to "Algieba", "Algieba" to "Zosma", "Zosma" to "Denebola",
            "Denebola" to "Chertan", "Chertan" to "Regulus"
        ),
        "Gem" to listOf("Castor" to "Pollux", "Pollux" to "Alhena", "Alhena" to "Castor"),
        "Vir" to listOf("Spica" to "Porrima", "Porrima" to "Vindemiatrix"),
        "Boo" to listOf("Arcturus" to "Izar", "Arcturus" to "Muphrid", "Izar" to "Nekkar"),
        "Aur" to listOf("Capella" to "Menkalinan", "Capella" to "Hassaleh"),
        "CMa" to listOf("Sirius" to "Mirzam", "Sirius" to "Adhara", "Adhara" to "Wezen", "Wezen" to "Aludra"),
        "CMi" to listOf("Procyon" to "Gomeisa"),
        "And" to listOf("Alpheratz" to "Mirach"),
        "Per" to listOf("Mirfak" to "Algol"),
        "Cas" to listOf(
            "Caph" to "Schedar", "Schedar" to "Navi", "Navi" to "Ruchbah", "Ruchbah" to "Segin"
        ),
        "Cru" to listOf("Acrux" to "Mimosa", "Mimosa" to "Gacrux", "Gacrux" to "Acrux")
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
