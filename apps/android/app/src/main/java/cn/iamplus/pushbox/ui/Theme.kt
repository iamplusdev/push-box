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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** §9.1 里那些没有 Material3 归属的 token：页面底、气泡、栏底、分割线、级别色条、离线态。 */
@Immutable
data class PushBoxColors(
    val pageBackground: Color,
    val bubble: Color,
    val rowBackground: Color,
    val topBar: Color,
    val bottomBar: Color,
    val divider: Color,
    val textPrimary: Color,
    val levelError: Color,
    val levelWarning: Color,
    val levelInfo: Color,
    val offline: Color,
    val unreadBadge: Color,
)

private val LightPushBoxColors = PushBoxColors(
    pageBackground = Color(0xFFF5F5F5),
    bubble = Color(0xFFFFFFFF),
    rowBackground = Color(0xFFFFFFFF),
    topBar = Color(0xFFEDEDED),
    bottomBar = Color(0xFFF7F7F7),
    divider = Color(0xFFDEDEDE),
    textPrimary = Color(0xFF161415),
    levelError = Color(0xFFF5222D),
    levelWarning = Color(0xFFFF8C00),
    levelInfo = Color(0xFF757575),
    offline = Color(0xFFFF8C00),
    unreadBadge = Color(0xFFF5222D),
)

private val DarkPushBoxColors = PushBoxColors(
    pageBackground = Color(0xFF1A1A1A),
    bubble = Color(0xFF242424),
    rowBackground = Color(0xFF1F1F1F),
    topBar = Color(0xFF141414),
    bottomBar = Color(0xFF141414),
    divider = Color(0xFF333333),
    textPrimary = Color(0xFFE0E0E0),
    levelError = Color(0xFFFF5252),
    levelWarning = Color(0xFFFFB74D),
    levelInfo = Color(0xFF9E9E9E),
    offline = Color(0xFFFFA726),
    unreadBadge = Color(0xFFFF4D4F),
)

// 微信式中性色：不做 dynamic color，否则一台手机一套配色，"像微信"这件事就没了。
//
// 必须把 surfaceContainer* / secondaryContainer / primaryContainer 这一整套一起写掉：
// lightColorScheme() 只覆盖传进去的那几项，剩下的仍是 Material3 基线的**紫**
// （#E6E0E9、#E8DEF8、#B69DF8 那一族）。而 `Card` 的默认底就是 `surfaceContainerHigh`、
// 填充式 `TextField` 的容器是 `surfaceVariant` 叠 `onSurface`、提示条用 `secondaryContainer`——
// 于是真机上频道页那几张卡是淡紫色的，而 §9.1 说的是"微信式中性"。少写一项就是一处漏色，
// 且它只在真机上看得出来，编译期与预览都不报错。
// 主色要同时当"实底"和"文字"：亮绿 #0ABF5E 在白底上只有 2.4:1，被拿来当 12sp 选中标签时读不清。
// 浅色取深一档的 #0F7A4F（白字 5.4:1、自己当正文色 5.1:1）配白字；
// 深色反过来取亮一档的 #3FBF7A（#141414 上 7.9:1）配深墨绿，与 PC 的 accent/onAccent 同值。
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F7A4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6F1E1),
    onPrimaryContainer = Color(0xFF053A1E),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF161415),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161415),
    surfaceVariant = Color(0xFFEEEEEE),
    // 次要字要落在页面/列表/底栏三种底上都 ≥4.5:1：#757575 在 #F7F7F7 上只有 4.3
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceDim = Color(0xFFE6E6E6),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    secondary = Color(0xFF0F7A4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFF333333),
    // 抬升着色：基线是 primary（绿），叠在卡片上会让白卡泛绿；中性灰叠上去看不出来，
    // 层次仍由上面那几档 container 负责。
    surfaceTint = Color(0xFF9E9E9E),
    outline = Color(0xFFDEDEDE),
    outlineVariant = Color(0xFFDEDEDE),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3FBF7A),
    onPrimary = Color(0xFF08170E),
    primaryContainer = Color(0xFF0A3D22),
    onPrimaryContainer = Color(0xFFBCE8CD),
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceDim = Color(0xFF151515),
    surfaceBright = Color(0xFF333333),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1D1D1D),
    surfaceContainer = Color(0xFF212121),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    secondary = Color(0xFF3FBF7A),
    onSecondary = Color(0xFF08170E),
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color(0xFFDDDDDD),
    surfaceTint = Color(0xFF6E6E6E),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF333333),
)

/** §9.2 的字号规定：正文 15sp / 行高 1.55，会话名 16sp，摘要 13sp，时间 12sp。 */
private val PushBoxTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    // 字号归字号，颜色归调用方：原先在这里焊死 #8A8A8A（在 #F7F7F7 上约 3.4:1），
    // 没显式传色的地方就都继承这一档灰，底栏标签与日期分节首当其冲。
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
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
