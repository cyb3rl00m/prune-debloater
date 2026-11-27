package io.github.cyb3rl00m.prune.ui.components

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.model.PackageStatus
import io.github.cyb3rl00m.prune.core.model.Risk
import io.github.cyb3rl00m.prune.ui.theme.LocalRiskColors
import io.github.cyb3rl00m.prune.ui.theme.NumberStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PruneCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // D-pad / keyboard focus (TV remotes): a clear outline, since the default focus highlight is barely visible.
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.large)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        color = color,
        tonalElevation = 0.dp,
        // Flat card with a hairline edge; D-pad focus thickens the edge instead of adding a glow.
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding).animateContentSize(), content = content)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun RiskBadge(risk: Risk, modifier: Modifier = Modifier) {
    Pill(risk.label, LocalRiskColors.current.of(risk), modifier, if (risk == Risk.LOCKED) Icons.Outlined.Lock else null)
}

@Composable
fun StatusBadge(status: PackageStatus, modifier: Modifier = Modifier) {
    val c = when (status) {
        PackageStatus.ENABLED -> MaterialTheme.colorScheme.onSurfaceVariant
        PackageStatus.DISABLED -> MaterialTheme.colorScheme.tertiary
        PackageStatus.REMOVED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(status.label, c, modifier)
}

@Composable
fun RiskDot(risk: Risk, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(LocalRiskColors.current.of(risk)))
}

enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, modifier: Modifier = Modifier, title: String? = null,
           action: (@Composable () -> Unit)? = null) {
    val (color, icon) = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Outlined.Info
        BannerKind.WARNING -> MaterialTheme.colorScheme.tertiary to Icons.Outlined.WarningAmber
        BannerKind.ERROR -> MaterialTheme.colorScheme.error to Icons.Outlined.ErrorOutline
    }
    // A plain note: neutral background and a thin coloured rule on the left. No tinted fill.
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (action != null) {
                    Spacer(Modifier.height(6.dp))
                    action()
                }
            }
        }
        }
    }
}

@Composable
fun StatTile(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    // Number first, label underneath, a small grey icon. [accent] is kept for callers but no longer tints anything.
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = NumberStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

private val iconCache = LruCache<String, ImageBitmap>(300)

@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    @Suppress("DEPRECATION")
                    val ai = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    ai.loadIcon(pm).toBitmap(px, px).asImageBitmap()
                }.getOrNull()?.also { iconCache.put(packageName, it) }
            }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
fun PackageRow(
    app: AppPackage,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showStatus: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle ?: app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RiskBadge(app.risk)
                if (showStatus && app.status != PackageStatus.ENABLED) StatusBadge(app.status)
                if (app.classification.warnings.any { it.groupKey != "IN_USE" }) {
                    Pill(app.classification.warnings.first { it.groupKey != "IN_USE" }.title, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, icon: ImageVector, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.58f))
    }
}

fun formatDuration(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "<1 min"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60} min"
    }
}

fun daysAgo(time: Long?): String {
    if (time == null || time <= 0) return "never (in the last 90 days)"
    val d = (System.currentTimeMillis() - time) / (24L * 60 * 60 * 1000)
    return when (d) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$d days ago"
    }
}
