package se.oxelv.mintracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import se.oxelv.mintracker.data.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Revalidate on resume and after confirmed changes; no social content survives account disposal. */
@Composable fun socialRefreshRevision(userId: String): Pair<Long, Int> {
    val revisions by SocialChanges.revisions.collectAsStateWithLifecycle()
    var resume by remember(userId) { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, userId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resume++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return (revisions[userId] ?: 0L) to resume
}

fun socialFeedError(error: Exception): String {
    if (error is CancellationException) throw error
    val message = error.message.orEmpty().lowercase()
    return when {
        error is AccountChangedException -> "Kontot har ändrats. Öppna vyn igen."
        "40001" in message || "revision" in message -> "Inställningen har ändrats på en annan enhet. Läs om och försök igen."
        "42501" in message || "unavailable" in message -> "Innehållet är inte längre tillgängligt. Uppdatera vyn."
        else -> "Kunde inte hämta eller spara. Kontrollera internet och försök igen."
    }
}

@Composable fun SocialActivityFeedScreen(userId: String, onTab: (Int) -> Unit, onProfile: (String) -> Unit, onChallenge: (String) -> Unit) = key(userId) {
    SocialActivityFeedContent(userId, onTab, onProfile, onChallenge)
}
@Composable private fun SocialActivityFeedContent(userId: String, onTab: (Int) -> Unit, onProfile: (String) -> Unit, onChallenge: (String) -> Unit) {
    val repository = remember { SocialFeedRepository(userId) }
    val refresh = socialRefreshRevision(userId)
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<SocialEvent>>(emptyList()) }
    var cursor by remember { mutableStateOf<SocialCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var cheering by remember { mutableStateOf<Set<String>>(emptySet()) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, reload) {
        generation++; loading = true; error = null; events = emptyList(); cursor = null
        try { val page = repository.feed(); events = page.items; cursor = page.nextCursor }
        catch (e: Exception) { error = socialFeedError(e) }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aktiviteter", style = MaterialTheme.typography.titleLarge)
        SocialTabs(5, onTab)
        Text("Delad träning från dina vänner. Platsdata och anteckningar visas aldrig.", color = AppColors.Muted)
        TextButton(onClick = { reload++ }, enabled = !loading) { Text("Uppdatera") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it) { reload++ } }
        if (!loading && error == null && events.isEmpty()) EmptyState("Inga aktiviteter ännu. När dina vänner delar träning visas den här.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(events, key = { it.id }) { event ->
                AppCard(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onProfile(event.actorId) }) {
                        ProfileAvatar(Profile(event.actorId, event.displayName, avatarPath = event.avatarPath))
                        Spacer(Modifier.width(10.dp)); Text(event.displayName)
                    }
                    Text(SocialFeedText.description(event), style = MaterialTheme.typography.titleMedium)
                    if (event.eventType != "CHALLENGE_COMPLETED") {
                        Text("${socialKm(event.distanceMeters ?: 0.0)} · ${(event.durationSeconds ?: 0) / 60} min")
                        event.paceSecondsPerKm?.let { Text("${socialPace(it)} /km", color = AppColors.Muted) }
                    }
                    Text(runCatching { OffsetDateTime.parse(event.occurredAt).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy 'kl' HH", Locale("sv", "SE"))) }.getOrDefault(""), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = event.id !in cheering, onClick = {
                            val requestGeneration = generation
                            cheering = cheering + event.id
                            scope.launch {
                                try {
                                    val result = repository.cheer(event.id, !event.cheeredByMe)
                                    if (generation == requestGeneration) events = events.map { if (it.id == event.id) it.copy(cheerCount = result.count, cheeredByMe = result.mine) else it }
                                } catch (e: Exception) {
                                    error = socialFeedError(e)
                                    events = emptyList(); cursor = null
                                } finally { cheering = cheering - event.id }
                            }
                        }) { Text("${if (event.cheeredByMe) "♥" else "♡"} ${event.cheerCount} Heja") }
                        event.challengeId?.let { id -> TextButton(onClick = { onChallenge(id) }) { Text("Visa utmaning") } }
                    }
                }
            }
            cursor?.let { next -> item {
                TextButton(enabled = !loading, onClick = {
                    val requestGeneration = generation
                    scope.launch {
                        loading = true; error = null
                        try {
                            val page = repository.feed(next)
                            if (generation == requestGeneration) { events = (events + page.items).distinctBy { it.id }; cursor = page.nextCursor }
                        } catch (e: Exception) { if (generation == requestGeneration) error = socialFeedError(e) }
                        finally { if (generation == requestGeneration) loading = false }
                    }
                }) { Text("Visa fler") }
            } }
        }
    }
}

@Composable fun SocialPrivacySettings(userId: String) = key(userId) { SocialPrivacyContent(userId) }
@Composable private fun SocialPrivacyContent(userId: String) {
    val repository = remember { SocialFeedRepository(userId) }
    val refresh = socialRefreshRevision(userId)
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SocialPrivacy?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, reload) {
        busy = true; state = null; error = null
        try { state = repository.privacy() } catch (e: Exception) { error = socialFeedError(e) } finally { busy = false }
    }
    fun save(auto: Boolean, stats: Boolean) {
        val previous = state ?: return
        busy = true; error = null
        scope.launch {
            try { state = repository.setPrivacy(previous, auto, stats) }
            catch (e: Exception) { error = socialFeedError(e); state = null }
            finally { busy = false }
        }
    }
    AppCard(Modifier.fillMaxWidth()) {
        Text("Delning och integritet", style = MaterialTheme.typography.titleMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it) { reload++ } }
        state?.let { value ->
            Row { Switch(value.autoShare, { save(it, value.showStatistics) }, enabled = !busy); Text("Dela nya pass med vänner", Modifier.padding(10.dp)) }
            Text("Av från början. Äldre privata pass delas inte. Inställningen delar också nya måluppfyllelser i gemensamma utmaningar, utan privata passdetaljer. Redan delade pass ändras under respektive pass.", style = MaterialTheme.typography.bodySmall)
            Row { Switch(value.showStatistics, { save(value.autoShare, it) }, enabled = !busy); Text("Visa offentlig profilstatistik", Modifier.padding(10.dp)) }
            Text("Kräver offentlig profil. Endast delade pass räknas. Privata pass, GPS och anteckningar visas aldrig.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun ActivitySharingSettings(activity: ActivityEntity) = key(activity.userId, activity.cloudId) { ActivitySharingContent(activity) }
@Composable private fun ActivitySharingContent(activity: ActivityEntity) {
    val userId = activity.userId ?: return
    val id = activity.cloudId ?: return
    val repository = remember { SocialFeedRepository(userId) }
    val scope = rememberCoroutineScope()
    val refresh = socialRefreshRevision(userId)
    var state by remember { mutableStateOf<ActivitySharing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, reload, activity.syncStatus) {
        state = null; error = null
        if (activity.syncStatus != SyncStatus.SYNCED) return@LaunchedEffect
        busy = true
        try { state = repository.sharing(id) } catch (e: Exception) { error = socialFeedError(e) } finally { busy = false }
    }
    fun save(friends: Boolean, dog: Boolean) {
        val previous = state ?: return
        busy = true; error = null
        scope.launch {
            try { state = repository.setSharing(id, previous, friends, dog) }
            catch (e: Exception) { error = socialFeedError(e); state = null }
            finally { busy = false }
        }
    }
    AppCard(Modifier.fillMaxWidth()) {
        Text("Synlighet", style = MaterialTheme.typography.titleMedium)
        if (activity.syncStatus != SyncStatus.SYNCED) Text("Synka passet först för att välja Privat eller Vänner.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it) { reload++ } }
        state?.let { value ->
            Row { Switch(value.visibility == "FRIENDS", { save(it, value.shareDogName) }, enabled = !busy); Text(if (value.visibility == "FRIENDS") "Vänner" else "Privat", Modifier.padding(10.dp)) }
            Text("Vänner ser typ, distans, tid och tempo. Rutt och anteckningar förblir privata.", style = MaterialTheme.typography.bodySmall)
            if (activity.dogId != null && value.visibility == "FRIENDS") Row {
                Switch(value.shareDogName, { save(true, it) }, enabled = !busy); Text("Visa hundens namn", Modifier.padding(10.dp))
            }
        }
    }
}

@Composable fun PublicProfileStatistics(userId: String, profileId: String) = key(userId, profileId) { PublicStatisticsContent(userId, profileId) }
@Composable private fun PublicStatisticsContent(userId: String, profileId: String) {
    val repository = remember { SocialFeedRepository(userId) }
    val refresh = socialRefreshRevision(userId)
    var stats by remember { mutableStateOf<PublicStatistics?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, reload) {
        stats = null; loading = true; error = null
        try { stats = repository.statistics(profileId) } catch (e: Exception) { error = socialFeedError(e) } finally { loading = false }
    }
    Text("Statistik", style = MaterialTheme.typography.titleMedium)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { ErrorState(it) { reload++ } }
    stats?.let { value ->
        when (value.status) {
            "PRIVATE" -> Text("Profilens statistik är privat.", color = AppColors.Muted)
            "EMPTY" -> EmptyState("Inga delade pass att visa ännu.")
            "VISIBLE" -> {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("${value.count} pass · ${socialKm(value.distanceMeters)}", style = MaterialTheme.typography.titleLarge)
                    Text("${value.durationSeconds / 3600} h ${(value.durationSeconds % 3600) / 60} min · ${value.runCount} löppass · ${value.walkCount} promenader")
                    Text("Senaste 7 dagarna: ${socialKm(value.last7)}")
                    Text("Senaste 30 dagarna: ${socialKm(value.last30)}")
                    Text("Löptempo: ${value.runPace?.let(::socialPace) ?: "–"} /km")
                    Text("Längsta löppass: ${socialKm(value.longestRun)}")
                    Text("${value.completedChallenges} avklarade utmaningar med delade pass")
                }
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Delad träning – senaste 8 veckorna", style = MaterialTheme.typography.titleMedium)
                    val maximum = value.weeks.maxOfOrNull { it.distanceMeters }?.coerceAtLeast(1.0) ?: 1.0
                    value.weeks.forEach { week ->
                        Text("${week.weekStart} · ${socialKm(week.distanceMeters)} · ${week.count} pass", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { (week.distanceMeters / maximum).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("Endast delade pass. Privat träning ingår inte.", color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun socialKm(meters: Double) = String.format(Locale("sv", "SE"), "%.1f km", meters / 1000)
private fun socialPace(seconds: Double): String {
    val rounded = seconds.toLong().coerceAtLeast(0)
    return "%d:%02d".format(rounded / 60, rounded % 60)
}
