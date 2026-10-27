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
    // 未读气泡是白字，底色就得压到能让白字站住：#F5222D 上白字只有 4.1:1，
    // 12sp 的小字不够 AA；#D32F2F 是 4.98:1，肉眼看还是同一个红。
    unreadBadge = Color(0xFFD32F2F),
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
    // 同上：#FF4D4F 上白字只有 3.3:1；#C62828 是 5.6:1，且在 #1F1F1F 的会话行上仍分得出来。
    unreadBadge = Color(0xFFC62828),
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

/**
 * §9.2 的六档字号 {12, 13, 15, 16, 18, 20} 与按 1.53× 取整的行高（20→28、18→25、16→22、15→23、13→18、12→16）。
 *
 * 15 个槽位全部写掉，不是"没处用也占着"：没覆盖的槽位会落到 Material3 基线值，而基线里
 * `headlineSmall` 是 24sp、`titleSmall` 与 `labelLarge` 是 14sp——24 和 14 都不在六档里。
 * 真机上这三个槽位各自出过一次事故形状：PIN 键盘的数字比页面标题还大、通知频道名比正文大一号、
 * 设备状态标签也是 14。编译期不报、预览里也不容易看出来，所以这里一次写完，
 * 界面里任何一处 `MaterialTheme.typography.*` 都只可能落在合法档位上（`TypographyTest` 钉住）。
 *
 * display 三档在 §9.2 里没有对应层级，压到 H1：基线 57sp 的 displayLarge 一出现就会糊满一屏。
 */
internal val PushBoxTypography = Typography(
    displayLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    // 按钮文字走 labelLarge。13 而不是 12：中文按钮在 12sp 下笔画会糊，13 仍属 Small 档。
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
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
