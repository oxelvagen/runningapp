package se.oxelv.mintracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppColors {
    val Blue = Color(0xFF168AFB)
    val BlueDark = Color(0xFF0875E1)
    val Navy = Color(0xFF062A43)
    val Background = Color(0xFFF3F7FD)
    val Surface = Color.White
    val Text = Color(0xFF102333)
    val Muted = Color(0xFF607285)
    val PaleBlue = Color(0xFFE4F1FF)
    val Track = Color(0xFFE7EDF4)
}

private val AppScheme = lightColorScheme(
    primary = AppColors.Blue, onPrimary = Color.White,
    background = AppColors.Background, onBackground = AppColors.Text,
    surface = AppColors.Surface, onSurface = AppColors.Text,
    surfaceVariant = AppColors.PaleBlue, onSurfaceVariant = AppColors.Muted,
    error = Color(0xFFB42332)
)

@Composable fun TrackerTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = AppScheme,
    typography = Typography(
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(color = AppColors.Text),
        bodySmall = MaterialTheme.typography.bodySmall.copy(color = AppColors.Muted)
    ), content = content
)

@Composable fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val clickable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Card(modifier = clickable, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = AppColors.Surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable fun PrimaryActionButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue, contentColor = Color.White)) {
        if (icon != null) { Icon(icon, contentDescription = null); Spacer(Modifier.width(8.dp)) }
        Text(label, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(index) },
                shape = RoundedCornerShape(14.dp),
                color = if (active) AppColors.Blue else AppColors.Track,
                contentColor = if (active) Color.White else AppColors.Text
            ) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable fun StatValue(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun WeeklyChart(values: List<Double>, modifier: Modifier = Modifier) {
    require(values.size == 7)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            val slot = size.width / 7f
            values.forEachIndexed { index, amount ->
                val height = (size.height * (amount / max).toFloat()).coerceAtLeast(if (amount > 0) 4f else 0f)
                drawRoundRect(
                    color = if (amount > 0) AppColors.Blue else AppColors.Track,
                    topLeft = androidx.compose.ui.geometry.Offset(index * slot + slot * .22f, size.height - height),
                    size = androidx.compose.ui.geometry.Size(slot * .56f, height.coerceAtLeast(3f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Mån", "Tis", "Ons", "Tor", "Fre", "Lör", "Sön").forEach { Text(it, modifier = Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = AppColors.Muted) }
        }
    }
}

@Composable fun EmptyState(text: String) { Text(text, color = AppColors.Muted, style = MaterialTheme.typography.bodyMedium) }
@Composable fun SocialTabs(selected: Int, onSelect: (Int) -> Unit) {
    SegmentedTabs(listOf("Upptäck", "Vänner", "Grupper", "Inkorg", "Utmaningar"), selected, onSelect)
}
@Composable fun ErrorState(text: String, onRetry: (() -> Unit)? = null) {
    Column { Text(text, color = MaterialTheme.colorScheme.error); if (onRetry != null) TextButton(onClick = onRetry) { Text("Försök igen") } }
}

@Composable fun AppBottomBar(selected: String, inboxCount: Int, onNavigate: (String) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 4.dp) {
        val items = listOf(
            Triple("home", "Hem", Icons.Outlined.Home),
            Triple("history", "Historik", Icons.Outlined.History),
            Triple("prepare", "Start", Icons.Outlined.PlayArrow),
            Triple("social", "Socialt", Icons.Outlined.Groups),
            Triple("profile", "Profil", Icons.Outlined.Person)
        )
        items.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = selected == route,
                onClick = { onNavigate(route) },
                icon = {
                    if (route == "prepare") Surface(shape = CircleShape, color = AppColors.Blue, contentColor = Color.White, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label) }
                    } else if (route == "social" && inboxCount > 0) BadgedBox(badge = { Badge { Text(inboxCount.toString()) } }) { Icon(icon, contentDescription = label) }
                    else Icon(icon, contentDescription = label)
                },
                label = { Text(label, fontSize = 11.sp) }, alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(selectedIconColor = AppColors.Blue, selectedTextColor = AppColors.Blue, unselectedIconColor = AppColors.Muted, unselectedTextColor = AppColors.Muted, indicatorColor = AppColors.PaleBlue)
            )
        }
    }
}
