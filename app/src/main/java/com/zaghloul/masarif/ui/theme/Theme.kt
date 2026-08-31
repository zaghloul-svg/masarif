package com.zaghloul.masarif.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zaghloul.masarif.data.Cat

private val Teal = Color(0xFF0E6E5C)
private val TealLight = Color(0xFF4FD1B0)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0E0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color.White,
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFEBF1EE),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF9FAAA6),
    outlineVariant = Color(0xFFDCE5E1),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFB9F0E0),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF171D1B),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF232B28),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF88938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * Eight categorical hues, validated for colour-vision separation and for contrast
 * against both surfaces. An earlier hand-picked 15-colour set failed: Entertainment
 * and Health sat 4.5 ΔE apart, which is indistinguishable even with normal vision.
 *
 * Eight is the ceiling, so charts show the top seven categories plus "Other" rather
 * than inventing a ninth hue. Three light-mode hues fall below 3:1 on the light
 * surface, so every chart that uses them ships a visible labelled breakdown beside
 * it — colour is never the only way to tell two slices apart.
 */
private val SlotsLight = listOf(
    Color(0xFF2A78D6), // 0 blue
    Color(0xFFEB6834), // 1 orange
    Color(0xFF1BAF7A), // 2 aqua
    Color(0xFFEDA100), // 3 yellow
    Color(0xFFE87BA4), // 4 magenta
    Color(0xFF008300), // 5 green
    Color(0xFF4A3AA7), // 6 violet
    Color(0xFFE34948)  // 7 red
)

private val SlotsDark = listOf(
    Color(0xFF3987E5),
    Color(0xFFD95926),
    Color(0xFF199E70),
    Color(0xFFC98500),
    Color(0xFFD55181),
    Color(0xFF008300),
    Color(0xFF9085E9),
    Color(0xFFE66767)
)

private val NeutralLight = Color(0xFF8A8A85)
private val NeutralDark = Color(0xFF9A9A93)

/**
 * Fixed category -> hue slot. Colour follows the category, never its rank, so a
 * quiet month never repaints the categories that survive into the chart.
 *
 * The eight categories that dominate a typical month hold unique slots; the long
 * tail reuses a slot it is unlikely to appear alongside, and the labelled
 * breakdown resolves any overlap.
 */
private val CategorySlot: Map<String, Int> = mapOf(
    Cat.TRANSPORT to 0,
    Cat.DINING to 1,
    Cat.GROCERIES to 2,
    Cat.SHOPPING to 3,
    Cat.HEALTH to 4,
    Cat.BILLS to 5,
    Cat.SUBSCRIPTIONS to 6,
    Cat.UTILITIES to 7,
    // long tail, paired with a category it rarely tops the chart beside
    Cat.TRAVEL to 0,
    Cat.ENTERTAINMENT to 4,
    Cat.CASH to 3,
    Cat.EDUCATION to 6,
    Cat.FEES to 7,
    Cat.INCOME to 5
)

object MasarifColors {
    var isDark: Boolean = false
        internal set

    fun category(name: String): Color {
        if (name == Cat.OTHER) return if (isDark) NeutralDark else NeutralLight
        val slots = if (isDark) SlotsDark else SlotsLight
        val slot = CategorySlot[name] ?: return if (isDark) NeutralDark else NeutralLight
        return slots[slot % slots.size]
    }

    /** Slices a breakdown into the top [max] categories plus a folded "Other". */
    fun foldToPalette(
        entries: List<Pair<String, Double>>,
        max: Int = 7
    ): List<Pair<String, Double>> {
        if (entries.size <= max + 1) return entries
        val sorted = entries.sortedByDescending { it.second }
        val head = sorted.take(max)
        val tail = sorted.drop(max).sumOf { it.second }
        return if (tail > 0) head + (Cat.OTHER to tail) else head
    }

    val positive: Color get() = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val negative: Color get() = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    val warning: Color get() = if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
    val muted: Color get() = if (isDark) Color(0xFF8A9A95) else Color(0xFF6B7A75)
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MasarifTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MasarifColors.isDark = darkTheme
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
