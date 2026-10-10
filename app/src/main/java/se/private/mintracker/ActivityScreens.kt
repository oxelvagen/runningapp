package se.oxelv.mintracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.oxelv.mintracker.data.*
import se.oxelv.mintracker.tracking.KmSplit
import se.oxelv.mintracker.tracking.Session
import se.oxelv.mintracker.tracking.TrackPoint
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val sv = Locale("sv", "SE")
private fun km(meters: Double): String = "${NumberFormat.getNumberInstance(sv).apply { minimumFractionDigits = 1; maximumFractionDigits = 2 }.format(meters / 1000)} km"
private fun elapsed(ms: Long): String { val seconds = ms / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60) }
private fun durationLabel(ms: Long): String { val minutes = ms / 60000; return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min" }
private fun paceLabel(ms: Long, meters: Double): String = if (meters < 1) "--:--" else elapsed((ms / (meters / 1000.0)).toLong())
private fun activityDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", sv))
private fun greeting(): String = when (LocalTime.now().hour) { in 5..10 -> "God morgon"; in 11..16 -> "God dag"; else -> "God kväll" }

@Composable
fun HomeDashboard(profile: Profile?, activities: List<ActivityEntity>, dogs: List<DogEntity>, live: Session, onStart: () -> Unit, onLatest: (ActivityEntity) -> Unit, onHistory: () -> Unit, onStatistics: () -> Unit, onProfile: () -> Unit, challenge: ChallengeSummary? = null, challengeProgress: Double? = null, onChallenge: (String) -> Unit = {}) {
    val week = remember(activities) { ActivitySummary.week(activities) }
    val latest = activities.firstOrNull()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("${greeting()}, ${profile?.displayName?.substringBefore(' ') ?: "löpare"}!", style = MaterialTheme.typography.titleLarge); Text("Redo för dagens pass?", color = AppColors.Muted) }
                Surface(onClick = onProfile, shape = RoundedCornerShape(20.dp), color = AppColors.PaleBlue) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { if (profile != null) ProfileAvatar(profile) else Text("P", color = AppColors.Blue, fontWeight = FontWeight.Bold) }
                }
            }
        }
        item { AppCard(Modifier.fillMaxWidth()) {
            Text("Denna vecka", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(km(week.totals.distanceMeters), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppColors.Navy)
                Column(horizontalAlignment = Alignment.End) { Text("${week.totals.count} pass", fontWeight = FontWeight.SemiBold); Text(durationLabel(week.totals.durationMillis), color = AppColors.Muted) }
            }
            WeeklyChart(week.dailyDistanceMeters)
        } }
        item { OutlinedButton(onClick = onStatistics, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Visa statistik och diagram") } }
        item { PrimaryActionButton("STARTA PASS", icon = Icons.Outlined.DirectionsRun, enabled = !live.running, onClick = onStart) }
        if (challenge != null) item { AppCard(Modifier.fillMaxWidth(), { onChallenge(challenge.id) }) {
            Text("AKTIV UTMANING", color = AppColors.Blue, style = MaterialTheme.typography.labelLarge)
            Text(challenge.title, style = MaterialTheme.typography.titleMedium, color = AppColors.Navy)
            Text("${ChallengeRules.format(challenge.metricType, challengeProgress ?: 0.0)} / ${ChallengeRules.format(challenge.metricType, challenge.targetValue)}")
            LinearProgressIndicator(progress = { (ChallengeRules.percent(challengeProgress ?: 0.0, challenge.targetValue) / 100.0).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
        } }
        if (live.running) item { AppCard(Modifier.fillMaxWidth()) { Text("Ett pass pågår", fontWeight = FontWeight.SemiBold); Text("Öppna passet för att pausa eller avsluta.") } }
        item { SectionHeader("Senaste aktivitet", "Se alla", onHistory) }
        if (latest == null) item { AppCard(Modifier.fillMaxWidth()) { EmptyState("Dina sparade pass visas här.") } }
        else item { ActivityListCard(latest, dogs.firstOrNull { it.id == latest.dogId }?.name) { onLatest(latest) } }
    }
}

@Composable
fun PrepareActivityScreen(kind: String, dogId: String?, dogs: List<DogEntity>, gpsReady: Boolean, accuracy: Float, permissionReady: Boolean, location: TrackPoint?, onKind: (String) -> Unit, onDog: (String) -> Unit, onStart: () -> Unit, onPermission: () -> Unit, onSettings: () -> Unit, onManageDogs: () -> Unit) {
    val choices = ActivityKind.entries
    val selectedKind = ActivityKind.fromStored(kind) ?: ActivityKind.RUN
    val activeDogs = dogs.filter { it.archivedAt == null }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Starta pass", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Inställningar") }
        }
        SegmentedTabs(choices.map { it.label }, choices.indexOf(selectedKind), { onKind(choices[it].name) })
        if (selectedKind.requiresDog) AppCard(Modifier.fillMaxWidth()) {
            Text("Vilken hund följer med?", style = MaterialTheme.typography.titleMedium)
            if (activeDogs.isEmpty()) Text("Lägg till en hund innan du startar ett hundpass.", color = AppColors.Muted)
            else SegmentedTabs(activeDogs.map { it.name }, activeDogs.indexOfFirst { it.id == dogId }, { onDog(activeDogs[it].id) })
            activeDogs.firstOrNull { it.id == dogId }?.let { dog -> Row(verticalAlignment = Alignment.CenterVertically) { DogAvatar(dog); Spacer(Modifier.width(10.dp)); Text(dog.name) } }
            TextButton(onClick = onManageDogs) { Text("Mina hundar") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
            TrackMap(location?.let { listOf(it) } ?: emptyList(), emptyList(), true, Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), shape = RoundedCornerShape(20.dp), color = Color.White) {
                Text(if (gpsReady) "● GPS redo" else "GPS söker…", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (gpsReady) Color(0xFF118447) else AppColors.Muted, fontWeight = FontWeight.SemiBold)
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Text("Redo att börja?", style = MaterialTheme.typography.titleMedium)
            Text(if (!permissionReady) "Platsbehörighet behövs" else if (gpsReady) "GPS-signal: ${if (accuracy <= 20f) "Stark" else "Tillgänglig"} · ±${accuracy.toInt()} m" else "Söker din position…", color = AppColors.Muted)
            if (!permissionReady) PrimaryActionButton("GE PLATSBEHÖRIGHET", onClick = onPermission)
            else PrimaryActionButton("STARTA PASS", enabled = gpsReady && (!selectedKind.requiresDog || activeDogs.any { it.id == dogId }), icon = Icons.Outlined.DirectionsRun, onClick = onStart)
        }
    }
}

@Composable
fun ActiveActivityScreen(session: Session, dogName: String?, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(listOfNotNull(ActivityKind.label(session.kind), dogName).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
        Text(elapsed(session.activeMs), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = AppColors.Navy, maxLines = 1)
        Text(if (session.paused) "PAUSAD" else "TID", style = MaterialTheme.typography.labelLarge, color = AppColors.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatValue(km(session.distanceM), "Distans")
            StatValue(if (session.running) if (session.currentPaceSeconds > 0) elapsed(session.currentPaceSeconds * 1000L) else "--:--" else paceLabel(session.activeMs, session.distanceM), "Tempo /km")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("${NumberFormat.getNumberInstance(sv).apply { maximumFractionDigits = 1 }.format(session.speedKmh)} km/h", color = AppColors.Muted)
            session.steps?.let { Text("$it steg", color = AppColors.Muted) }
            session.ascentM?.let { Text("${it.toInt()} m stigning", color = AppColors.Muted) }
        }
        TrackMap(session.points, session.splits, true, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)))
        if (session.paused) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onResume, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Icon(Icons.Outlined.PlayArrow, null); Text("Fortsätt") }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Avsluta") }
        } else PrimaryActionButton("PAUSA", icon = Icons.Outlined.Pause, onClick = onPause)
    }
}

@Composable
fun SessionResultScreen(session: Session, dogName: String?, saving: Boolean, error: String?, onSave: (String?) -> Unit, onDiscard: () -> Unit, allowDiscard: Boolean = true) {
    var note by remember(session.startedAt) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Passresultat", style = MaterialTheme.typography.titleLarge)
        Text("Bra jobbat!", style = MaterialTheme.typography.headlineSmall)
        dogName?.let { Text("Tillsammans med $it", color = AppColors.Muted) }
        Text("Du har genomfört ett nytt pass.", color = AppColors.Muted)
        TrackMap(session.points, session.splits, false, Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(20.dp)))
        AppCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatValue(km(session.distanceM), "Distans")
            StatValue(elapsed(session.activeMs), "Tid")
            StatValue(paceLabel(session.activeMs, session.distanceM), "Tempo /km")
        } }
        ActivityMetrics(session.ascentM, session.descentM, session.steps, session.speedKmh)
        SplitBars(session.splits)
        OutlinedTextField(value = note, onValueChange = { if (it.length <= ActivityNote.MAX_LENGTH) note = it }, label = { Text("Anteckning om passet (valfritt)") }, minLines = 3, maxLines = 6, supportingText = { Text("${note.length} / ${ActivityNote.MAX_LENGTH}") }, modifier = Modifier.fillMaxWidth())
        PrimaryActionButton("SPARA PASS", enabled = !saving, icon = Icons.Outlined.Save, onClick = { onSave(note) })
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it) }
        if (allowDiscard) TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Kasta pass") }
    }
}

@Composable
fun HistoryActivityScreen(activities: List<ActivityEntity>, dogs: List<DogEntity>, onStatistics: () -> Unit, onOpen: (ActivityEntity) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    var selectedDog by remember { mutableStateOf<String?>(null) }
    val kinds = ActivityKind.entries
    val labels = listOf("Alla") + kinds.map { it.label }
    val filtered = activities.filter { activity ->
        (selected == 0 || ActivityKind.fromStored(activity.kind) == kinds[selected - 1]) &&
            (selectedDog == null || activity.dogId == selectedDog)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Historik", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onStatistics) { Text("Statistik") }
        }
        SegmentedTabs(labels, selected, { selected = it })
        if (dogs.isNotEmpty()) SegmentedTabs(listOf("Alla hundar") + dogs.map { it.name }, dogs.indexOfFirst { it.id == selectedDog } + 1, { selectedDog = if (it == 0) null else dogs[it - 1].id })
        if (filtered.isEmpty()) AppCard(Modifier.fillMaxWidth()) { EmptyState("Inga pass att visa.") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { activity -> ActivityListCard(activity, dogs.firstOrNull { it.id == activity.dogId }?.name) { onOpen(activity) } }
        }
    }
}

@Composable
private fun ActivityListCard(activity: ActivityEntity, dogName: String?, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(ActivityKind.label(activity.kind), color = AppColors.Blue, fontWeight = FontWeight.SemiBold); dogName?.let { Text(it, color = AppColors.Muted) }; Text(km(activity.distanceM), style = MaterialTheme.typography.titleLarge) }
            Column(horizontalAlignment = Alignment.End) { Text(elapsed(activity.activeMs), fontWeight = FontWeight.SemiBold); Text("${paceLabel(activity.activeMs, activity.distanceM)} /km", color = AppColors.Muted) }
        }
        Text(activityDate(activity.startedAt), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ActivityDetailScreen(activity: ActivityEntity, dogName: String?, points: List<TrackPoint>, splits: List<KmSplit>, syncText: String, noteError: String?, deleting: Boolean, deleteError: String?, shareBusy: Boolean, shareError: String?, onSaveNote: (String?) -> Unit, onDelete: () -> Unit, onShare: (ShareFormat, ShareOptions) -> Unit, onSaveImage: (ShareFormat, ShareOptions) -> Unit, onBack: () -> Unit) {
    var editingNote by remember(activity.id) { mutableStateOf(false) }
    var noteDraft by remember(activity.id, editingNote) { mutableStateOf(activity.note.orEmpty()) }
    var moreOpen by remember(activity.id) { mutableStateOf(false) }
    var confirmDelete by remember(activity.id) { mutableStateOf(false) }
    var exportDialog by remember(activity.id) { mutableStateOf<String?>(null) }
    var shareFormat by remember(activity.id) { mutableStateOf(ShareFormat.POST) }
    var shareOptions by remember(activity.id) { mutableStateOf(ShareOptions(map = points.isNotEmpty())) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Historik") }
            Box {
                IconButton(onClick = { moreOpen = true }, enabled = !deleting) { Icon(Icons.Outlined.MoreVert, contentDescription = "Fler alternativ") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("Radera pass", color = MaterialTheme.colorScheme.error) }, onClick = { moreOpen = false; confirmDelete = true })
                }
            }
        }
        Text("Passresultat", style = MaterialTheme.typography.titleLarge)
        Text("${ActivityKind.label(activity.kind)}${dogName?.let { " · $it" }.orEmpty()} · ${activityDate(activity.startedAt)}", color = AppColors.Muted)
        TrackMap(points, splits, false, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp)))
        AppCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatValue(km(activity.distanceM), "Distans")
            StatValue(elapsed(activity.activeMs), "Tid")
            StatValue(paceLabel(activity.activeMs, activity.distanceM), "Tempo /km")
        } }
        ActivityMetrics(activity.ascentM, activity.descentM, activity.steps, if (activity.activeMs > 0) activity.distanceM / activity.activeMs * 3600 else 0.0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { exportDialog = "share" }, enabled = !shareBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Dela") }
            OutlinedButton(onClick = { exportDialog = "save" }, enabled = !shareBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Spara bild") }
        }
        if (shareBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        shareError?.let { ErrorState(it) }
        SplitBars(splits)
        AppCard(Modifier.fillMaxWidth()) {
            Text("Anteckning", style = MaterialTheme.typography.titleMedium)
            noteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (editingNote) {
                OutlinedTextField(value = noteDraft, onValueChange = { if (it.length <= ActivityNote.MAX_LENGTH) noteDraft = it }, label = { Text("Anteckning om passet") }, minLines = 3, maxLines = 6, supportingText = { Text("${noteDraft.length} / ${ActivityNote.MAX_LENGTH}") }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { editingNote = false }) { Text("Avbryt") }
                    TextButton(onClick = { onSaveNote(noteDraft); editingNote = false }) { Text("Spara") }
                    if (activity.note != null) TextButton(onClick = { onSaveNote(null); editingNote = false }) { Text("Ta bort") }
                }
            } else {
                activity.note?.let { Text(it) } ?: Text("Ingen anteckning", color = AppColors.Muted)
                TextButton(onClick = { editingNote = true }) { Text(if (activity.note == null) "Lägg till anteckning" else "Redigera anteckning") }
            }
        }
        Text(syncText, style = MaterialTheme.typography.bodySmall)
        if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
        deleteError?.let { ErrorState(it) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Radera pass?") }, text = { Text("Passet och dess sparade träningsdata tas bort permanent.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, enabled = !deleting) { Text("Radera", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") } })
    if (exportDialog != null) AlertDialog(
        onDismissRequest = { exportDialog = null },
        title = { Text(if (exportDialog == "share") "Dela pass" else "Spara delningsbild") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Format", fontWeight = FontWeight.SemiBold)
                SegmentedTabs(ShareFormat.entries.map { it.label }, shareFormat.ordinal, { shareFormat = ShareFormat.entries[it] })
                Text("Välj innehåll", fontWeight = FontWeight.SemiBold)
                @Composable fun toggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(label)
                        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
                    }
                }
                toggle("Karta och rutt", shareOptions.map, points.isNotEmpty()) { shareOptions = shareOptions.copy(map = it) }
                toggle("Distans", shareOptions.distance) { shareOptions = shareOptions.copy(distance = it) }
                toggle("Tid", shareOptions.duration) { shareOptions = shareOptions.copy(duration = it) }
                toggle("Tempo", shareOptions.pace) { shareOptions = shareOptions.copy(pace = it) }
                toggle("Hastighet", shareOptions.speed) { shareOptions = shareOptions.copy(speed = it) }
                toggle("Stigning", shareOptions.climb, activity.ascentM != null) { shareOptions = shareOptions.copy(climb = it) }
                toggle("Steg", shareOptions.steps, activity.steps != null) { shareOptions = shareOptions.copy(steps = it) }
                toggle("Hund", shareOptions.dog, dogName != null) { shareOptions = shareOptions.copy(dog = it) }
                toggle("Anteckning", shareOptions.note, !activity.note.isNullOrBlank()) { shareOptions = shareOptions.copy(note = it) }
                Text("Förhandsval: ${shareFormat.width} × ${shareFormat.height} px. ${if (shareOptions.map) "Kartbild och rutt ingår." else "Utan karta."}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = {
            val action = exportDialog
            exportDialog = null
            if (action == "share") onShare(shareFormat, shareOptions) else onSaveImage(shareFormat, shareOptions)
        }) { Text(if (exportDialog == "share") "Dela" else "Spara bild") } },
        dismissButton = { TextButton(onClick = { exportDialog = null }) { Text("Avbryt") } }
    )
}

@Composable
private fun ActivityMetrics(ascent: Double?, descent: Double?, steps: Int?, speed: Double?) {
    val metrics = buildList {
        if (steps != null) add("Steg" to "$steps")
        if (ascent != null) add("Stigning" to "${ascent.toInt()} m")
        if (descent != null) add("Utför" to "${descent.toInt()} m")
        if (speed != null && speed > 0) add("Hastighet" to "${NumberFormat.getNumberInstance(sv).apply { maximumFractionDigits = 1 }.format(speed)} km/h")
    }
    if (metrics.isNotEmpty()) AppCard(Modifier.fillMaxWidth()) { metrics.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = AppColors.Muted); Text(value, fontWeight = FontWeight.SemiBold) } } }
}

@Composable
fun SplitBars(splits: List<KmSplit>) {
    if (splits.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        Text("Tempo per kilometer", style = MaterialTheme.typography.titleMedium)
        val max = splits.maxOf { it.durationMs }.coerceAtLeast(1)
        splits.forEach { split ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${split.kilometer} km", Modifier.width(42.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (split.durationMs.toFloat() / max).coerceIn(0.05f, 1f) }, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)), color = AppColors.Blue, trackColor = AppColors.Track)
                Text(elapsed(split.durationMs), Modifier.width(48.dp), textAlign = TextAlign.End)
            }
        }
    }
}
