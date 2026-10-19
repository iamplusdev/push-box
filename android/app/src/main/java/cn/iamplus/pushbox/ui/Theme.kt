package cn.iamplus.pushbox.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** §9.1 里那些没有 Material3 归属的 token：页面底、气泡、级别色条、离线态。 */
@Immutable
data class PushBoxColors(
    val pageBackground: Color,
    val bubbleIn: Color,
    val levelError: Color,
    val levelWarning: Color,
    val levelInfo: Color,
    val offline: Color,
    val unreadBadge: Color,
)

private val LightPushBoxColors = PushBoxColors(
    pageBackground = Color(0xFFF5F5F5),
    bubbleIn = Color(0xFFE8F7F0),
    levelError = Color(0xFFF5222D),
    levelWarning = Color(0xFFFF8C00),
    levelInfo = Color(0xFF757575),
    offline = Color(0xFFFF8C00),
    unreadBadge = Color(0xFFF5222D),
)

private val DarkPushBoxColors = PushBoxColors(
    pageBackground = Color(0xFF1A1A1A),
    bubbleIn = Color(0xFF0D3D28),
    levelError = Color(0xFFFF5252),
    levelWarning = Color(0xFFFFB74D),
    levelInfo = Color(0xFF9E9E9E),
    offline = Color(0xFFFFA726),
    unreadBadge = Color(0xFFFF4D4F),
)

// 微信式中性色：不做 dynamic color，否则一台手机一套配色，"像微信"这件事就没了。
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0ABF5E),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFEEEEEE),
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF757575),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFE0E0E0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF0A8F4A),
    onPrimary = Color(0xFF1A1A1A),
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF333333),
)

/** §9.2 的字号规定：正文 15sp / 行高 1.55，会话名 16sp，摘要 13sp，时间 12sp。 */
private val PushBoxTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = FontFamily.Default,
        color = Color(0xFF8A8A8A),
    ),
)

val LocalPushBoxColors = staticCompositionLocalOf { LightPushBoxColors }

@Composable
fun PushBoxTheme(
    dark: Boolean = isSystemInDarkTheme(),
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = if (dark) DarkScheme else LightScheme
    // §7.4 字号：只放大字体不改 dp——保留 density、只按比例缩放 fontScale。
    val baseDensity = LocalDensity.current
    val density = if (textScale == 1f) baseDensity else Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * textScale,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = PushBoxTypography,
        content = {
            CompositionLocalProvider(
                LocalPushBoxColors provides
                    (if (dark) DarkPushBoxColors else LightPushBoxColors),
                LocalDensity provides density,
                content = content,
            )
        },
    )
}
