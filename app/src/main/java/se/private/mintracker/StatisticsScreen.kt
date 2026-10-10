package se.oxelv.mintracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.oxelv.mintracker.data.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val swedish = Locale("sv", "SE")
private fun distance(meters: Double) = "${NumberFormat.getNumberInstance(swedish).apply { maximumFractionDigits = 1 }.format(meters / 1000)} km"
private fun trainingTime(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

@Composable
fun StatisticsScreen(activities: List<ActivityEntity>, dogs: List<DogEntity>, onBack: () -> Unit,
                     onExport: (StatisticsPeriod, StatisticsFilter, StatisticsExportKind, StatisticsExportOptions) -> Unit = { _, _, _, _ -> },
                     exportBusy: Boolean = false, exportError: String? = null,
                     initialPeriod: StatisticsPeriod = StatisticsPeriod.SEVEN_DAYS,
                     initialKind: ActivityKind? = null, initialDogId: String? = null) {
    var period by remember { mutableStateOf(initialPeriod) }
    var kindIndex by remember { mutableIntStateOf(initialKind?.ordinal?.plus(1) ?: 0) }
    var dogId by remember { mutableStateOf(initialDogId) }
    val kinds = ActivityKind.entries
    val kind = if (kindIndex == 0) null else kinds[kindIndex - 1]
    LaunchedEffect(kind) { if (kind != ActivityKind.DOG_RUN && kind != ActivityKind.DOG_WALK) dogId = null }
    val stats = remember(activities, dogs, period, kind, dogId) {
        Statistics.calculate(activities, dogs, period, StatisticsFilter(kind, dogId))
    }
    var exportDialog by remember { mutableStateOf(false) }
    var exportKind by remember { mutableStateOf(StatisticsExportKind.IMAGE) }
    var exportOptions by remember { mutableStateOf(StatisticsExportOptions()) }
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false },
        title = { Text("Exportera statistik") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            StatisticsExportKind.entries.forEach { value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(exportKind == value, onClick = { exportKind = value })
                    Text(value.label)
                }
            }
            if (exportKind != StatisticsExportKind.EXCEL) {
                listOf(
                    "Sammanfattning" to exportOptions.summary,
                    "Distansdiagram" to exportOptions.distanceChart,
                    "Tempodiagram" to exportOptions.paceChart,
                    "Veckojämförelse" to exportOptions.weekComparison,
                    "Personliga rekord" to exportOptions.records,
                    "Aktivitetstabell" to exportOptions.activityTable,
                    "Hundstatistik" to exportOptions.dogStats,
                    "Anteckningar" to exportOptions.notes,
                    "Distans/pass" to exportOptions.averageDistance,
                    "Stigning" to exportOptions.climb
                ).forEachIndexed { index, (label, enabled) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enabled, onCheckedChange = { checked -> exportOptions = when (index) {
                            0 -> exportOptions.copy(summary = checked); 1 -> exportOptions.copy(distanceChart = checked)
                            2 -> exportOptions.copy(paceChart = checked); 3 -> exportOptions.copy(weekComparison = checked)
                            4 -> exportOptions.copy(records = checked); 5 -> exportOptions.copy(activityTable = checked)
                            6 -> exportOptions.copy(dogStats = checked); 7 -> exportOptions.copy(notes = checked)
                            8 -> exportOptions.copy(averageDistance = checked); else -> exportOptions.copy(climb = checked)
                        } })
                        Text(label)
                    }
                }
            }
            Text("${period.label} · ${kind?.label ?: "Alla"}${dogId?.let { id -> " · ${dogs.firstOrNull { it.id == id }?.name.orEmpty()}" } ?: ""}")
        } },
        confirmButton = { TextButton(onClick = { exportDialog = false; onExport(period, StatisticsFilter(kind, dogId), exportKind, exportOptions) }) { Text("Skapa") } },
        dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Avbryt") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Tillbaka") }
            Spacer(Modifier.width(8.dp))
            Text("Statistik", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { exportDialog = true }, enabled = !exportBusy) { Text("Exportera") }
        }
        if (exportBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Skapar rapport…") }
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SegmentedTabs(StatisticsPeriod.entries.map { it.label }, period.ordinal, { period = StatisticsPeriod.entries[it] })
        SegmentedTabs(listOf("Alla") + kinds.map { it.label }, kindIndex, { kindIndex = it })
        if (kind == ActivityKind.DOG_RUN || kind == ActivityKind.DOG_WALK) {
            val choices = listOf("Alla hundar") + dogs.map { it.name }
            SegmentedTabs(choices, dogs.indexOfFirst { it.id == dogId } + 1, { dogId = if (it == 0) null else dogs[it - 1].id })
        }
        if (stats.activities.isEmpty()) {
            AppCard(Modifier.fillMaxWidth()) { EmptyState("Inga pass under perioden ännu.") }
        } else {
            Surface(shape = RoundedCornerShape(22.dp), color = AppColors.Navy) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("TOTAL DISTANS", color = Color(0xFFB9D3E7), fontWeight = FontWeight.SemiBold)
                    Text(distance(stats.totals.distanceMeters), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Text("${stats.totals.count} pass  ·  ${trainingTime(stats.totals.durationMillis)}", color = Color.White)
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStat(Statistics.paceText(stats.paceSecondsPerKm), "Snittempo", Modifier.weight(1f))
                    MiniStat(stats.averageDistanceMeters?.let(::distance) ?: "–", "Distans/pass", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStat(stats.longest?.let { distance(it.distanceM) } ?: "–", "Längsta pass", Modifier.weight(1f))
                    MiniStat(stats.elevationGainMeters?.let { "${it.toInt()} m" } ?: "–", "Total stigning", Modifier.weight(1f))
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Text("Distans över tid", style = MaterialTheme.typography.titleMedium)
                DistanceChart(stats.buckets)
            }
            AppCard(Modifier.fillMaxWidth()) {
                Text("Tempoutveckling", style = MaterialTheme.typography.titleMedium)
                Text("Total tid / total distans per intervall", color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
                PaceChart(stats.buckets)
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Text("Den här veckan", style = MaterialTheme.typography.titleMedium)
            Text(distance(stats.thisWeek.distanceMeters), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppColors.Navy)
            Text("${stats.thisWeek.count} pass · ${trainingTime(stats.thisWeek.durationMillis)}", color = AppColors.Muted)
            val change = Statistics.weekChangePercent(stats.thisWeek.distanceMeters, stats.lastWeek.distanceMeters)
            Text(if (change == null) "Förra veckan: ${distance(stats.lastWeek.distanceMeters)}" else "${if (change > 0) "+" else ""}$change % mot förra veckan", color = AppColors.Blue)
        }
        if (stats.recordLongest != null) AppCard(Modifier.fillMaxWidth()) {
            Text("Personliga rekord", style = MaterialTheme.typography.titleMedium)
            Text("Alla tider inom valt aktivitetsfilter", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
            RecordRow("Längsta distans", stats.recordLongest.let { distance(it.distanceM) })
            RecordRow("Längsta träningstid", stats.recordDuration?.let { trainingTime(it.activeMs) } ?: "–")
            RecordRow("Största stigning", stats.recordClimb?.ascentM?.let { "${it.toInt()} m" } ?: "–")
        }
        if (stats.dogs.isNotEmpty()) {
            Text("Hundstatistik", style = MaterialTheme.typography.titleMedium)
            stats.dogs.forEach { dog ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { DogAvatar(dog.dog); Spacer(Modifier.width(10.dp)); Text(dog.dog.name, style = MaterialTheme.typography.titleMedium) }
                    Text("${distance(dog.distanceMeters)} tillsammans · ${dog.count} pass", fontWeight = FontWeight.SemiBold)
                    Text("${distance(dog.distanceMeters / dog.count)} per pass", color = AppColors.Muted)
                    Text("Löpning med hund: ${dog.runCount} · Promenad med hund: ${dog.walkCount}", color = AppColors.Muted)
                }
            }
        }
    }
}

@Composable private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(value, fontWeight = FontWeight.Bold, color = AppColors.Navy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun RecordRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppColors.Muted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun DistanceChart(buckets: List<ChartBucket>) {
    val max = buckets.maxOfOrNull { it.distanceMeters }?.coerceAtLeast(1.0) ?: 1.0
    val barWidth = if (buckets.size > 16) 24.dp else 38.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        buckets.forEach { bucket ->
            Column(Modifier.width(barWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(112.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.fillMaxWidth(.7f).height((112 * bucket.distanceMeters / max).coerceAtLeast(if (bucket.distanceMeters > 0) 3.0 else 2.0).dp), shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp), color = if (bucket.distanceMeters > 0) AppColors.Blue else AppColors.Track) {}
                }
                Text(bucket.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable private fun PaceChart(buckets: List<ChartBucket>) {
    val values = buckets.map { if (it.distanceMeters >= 100 && it.durationMs > 0) it.durationMs.toDouble() / it.distanceMeters else null }
    val valid = values.filterNotNull()
    if (valid.isEmpty()) { EmptyState("För lite distans för att visa tempo."); return }
    val min = valid.minOrNull() ?: 0.0
    val max = valid.maxOrNull() ?: min
    Canvas(Modifier.fillMaxWidth().height(112.dp)) {
        val step = if (values.size <= 1) 0f else size.width / (values.size - 1)
        var previous: Offset? = null
        values.forEachIndexed { index, pace ->
            if (pace == null) { previous = null; return@forEachIndexed }
            val fraction = if (max == min) .5f else ((pace - min) / (max - min)).toFloat()
            val point = Offset(index * step, 12f + fraction * (size.height - 24f))
            previous?.let { drawLine(AppColors.Blue, it, point, strokeWidth = 5f) }
            drawCircle(AppColors.Blue, 5f, point)
            previous = point
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(buckets.firstOrNull()?.label.orEmpty(), style = MaterialTheme.typography.labelSmall)
        Text(buckets.lastOrNull()?.label.orEmpty(), style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Snabbast ${Statistics.paceText(min)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Långsammast ${Statistics.paceText(max)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
