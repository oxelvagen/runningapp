package se.oxelv.mintracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import se.oxelv.mintracker.data.*

@Composable fun NotificationBell(repository: NotificationCenterRepository, onClick: () -> Unit) {
    val state by repository.state.collectAsState()
    val badge = NotificationText.badge(if (state.verified) state.unread else 0)
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (badge != null) Badge(containerColor = Color(0xFFD92D3A), contentColor = Color.White) { Text(badge) } }) {
            Icon(Icons.Default.Notifications, contentDescription = if (badge == null) "Öppna notiser" else "Notiser, $badge olästa", tint = AppColors.Text)
        }
    }
}

private fun notificationIcon(type: String): ImageVector = when(type) {
    "ACTIVITY_COMPLETED" -> Icons.Default.DirectionsRun
    "PERSONAL_RECORD" -> Icons.Default.EmojiEvents
    "CHEER" -> Icons.Default.Favorite
    "FRIEND_REQUEST", "FRIEND_ACCEPTED" -> Icons.Default.PersonAdd
    "GROUP_JOINED", "GROUP_MEMBER_JOINED" -> Icons.Default.Groups
    "CHALLENGE_CREATED", "CHALLENGE_COMPLETED", "CHALLENGE_INVITE", "CHALLENGE_LEADER_CHANGE", "CHALLENGE_ENDING_SOON" -> Icons.Default.Flag
    else -> Icons.Default.Info
}

@Composable fun NotificationCenterScreen(repository: NotificationCenterRepository, refreshKey: Int,
    onBack: () -> Unit, onSettings: () -> Unit, onTarget: (NotificationTarget) -> Unit) = key(repository.userId) {
    val snapshot by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var unreadOnly by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(snapshot.cached?.items.orEmpty()) }
    var cursor by remember { mutableStateOf<NotificationCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    LaunchedEffect(unreadOnly, reload, refreshKey, snapshot.revision) {
        generation++; loading = true; error = null; cursor = null
        // Don't show the preceding tab's data under a different filter.
        rows = if (unreadOnly) snapshot.cached?.items.orEmpty().filter { it.readAt == null } else snapshot.cached?.items.orEmpty()
        try {
            val result = repository.page(unreadOnly)
            rows = result.items; cursor = result.nextCursor; stale = false
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { stale = true; error = "Kan inte hämta notiser. Kontrollera anslutningen och försök igen."
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Tillbaka") }
            Text("Notiser", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Notisinställningar") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !unreadOnly, onClick = { if (!busy) unreadOnly = false }, label = { Text("Alla") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.PaleBlue, selectedLabelColor = AppColors.BlueDark))
            FilterChip(selected = unreadOnly, onClick = { if (!busy) unreadOnly = true }, label = { Text("Olästa") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.PaleBlue, selectedLabelColor = AppColors.BlueDark))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload++ }, enabled = !loading && !busy) { Icon(Icons.Default.Refresh, "Uppdatera notiser") }
        }
        TextButton(onClick = { scope.launch {
            busy = true; error = null
            try { repository.readAll(); rows = if (unreadOnly) emptyList() else rows.map { it.copy(readAt = java.time.Instant.now().toString()) }; reload++ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Notiserna kunde inte markeras som lästa. Försök igen." }
            finally { busy = false }
        } }, enabled = !busy && !loading && snapshot.unread > 0, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Markera alla som lästa") }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (stale && rows.isNotEmpty()) Text("Visar tidigare hämtade notiser. Innehållet kontrolleras online när du öppnar det.",
            Modifier.padding(16.dp), color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
        error?.let { message -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { reload++ }, enabled = !loading && !busy) { Text("Försök igen") }
        } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (!loading && rows.isEmpty() && error == null) item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NotificationsNone, null, Modifier.size(56.dp), tint = AppColors.Muted)
                    Spacer(Modifier.height(12.dp))
                    Text(if (unreadOnly) "Du har inga olästa notiser." else "Här dyker det upp nytt från dina vänner och grupper.", color = AppColors.Muted)
                }
            }
            items(rows, key = { it.id }) { notification ->
                NotificationRow(notification, tick, enabled = !busy && !loading) { scope.launch {
                    busy = true; error = null
                    try {
                        val target = repository.open(notification.id)
                        if (!target.available) { rows = rows.filterNot { it.id == notification.id }; error = "Innehållet är borttaget eller inte längre tillgängligt för dig." }
                        else {
                            rows = if (unreadOnly) rows.filterNot { it.id == notification.id } else rows.map { if (it.id == notification.id) it.copy(readAt = java.time.Instant.now().toString()) else it }
                            if (target.type == "SYSTEM") error = notification.payload.body ?: notification.payload.title ?: "Systemmeddelande"
                            else onTarget(target)
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = "Notisen kunde inte öppnas. Kontrollera anslutningen och försök igen." }
                    finally { busy = false }
                } }
            }
            if (cursor != null) item {
                TextButton(onClick = { val requested = cursor; val version = generation; scope.launch {
                    loading = true; error = null
                    try { val next = repository.page(unreadOnly, requested); if (version == generation) { rows = (rows + next.items).distinctBy { it.id }; cursor = next.nextCursor; stale = false } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Fler notiser kunde inte hämtas. Försök igen." }
                    finally { if (version == generation) loading = false }
                } }, enabled = !loading && !busy, modifier = Modifier.fillMaxWidth()) { Text("Visa fler") }
            }
        }
    }
}

@Composable private fun NotificationRow(n: TrackerNotification, tick: Int, enabled: Boolean, onClick: () -> Unit) {
    val unread = n.readAt == null
    val time = remember(n.createdAt, tick) { NotificationText.relative(n.createdAt) }
    Row(Modifier.fillMaxWidth().background(if (unread) AppColors.PaleBlue else AppColors.Surface)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(58.dp)) {
            if (n.actorId != null) ProfileAvatar(Profile(n.actorId, n.payload.name ?: "Användare", avatarPath = n.payload.avatarPath), 52.dp)
            else Surface(shape = CircleShape, color = AppColors.PaleBlue, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(notificationIcon(n.eventType), null, tint = AppColors.Blue) }
            }
            Surface(color = AppColors.Blue, shape = CircleShape, modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)) {
                Box(contentAlignment = Alignment.Center) { Icon(notificationIcon(n.eventType), null, Modifier.size(15.dp), tint = Color.White) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(buildAnnotatedString {
                if (n.actorId != null && n.eventType != "GROUP_JOINED") {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(n.payload.name ?: "En användare") }
                    if (n.payload.isNpc) append(" (simulerat konto)")
                    append(" ")
                }
                append(NotificationText.description(n))
            }, style = MaterialTheme.typography.bodyMedium, color = AppColors.Text)
            Text(time, color = if (unread) AppColors.Blue else AppColors.Muted, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (unread) Box(Modifier.size(9.dp).background(AppColors.Blue, CircleShape))
    }
    HorizontalDivider(color = AppColors.PaleBlue)
}

@Composable fun NotificationSettingsScreen(repository: NotificationCenterRepository, onBack: () -> Unit) = key(repository.userId) {
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf<NotificationPreferences?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        busy = true; error = null
        try { preferences = repository.preferences() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Inställningarna kunde inte hämtas. Kontrollera anslutningen." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Tillbaka") }
            Text("Notisinställningar", style = MaterialTheme.typography.titleLarge)
        }
        Text("Välj vad du vill se i notiscentret. Valen sparas för ditt konto.", color = AppColors.Muted)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }, enabled = !busy) { Text("Hämta igen") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(NotificationText.categories.entries.toList(), key = { it.key }) { entry ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.value, Modifier.weight(1f))
                        Switch(checked = preferences?.categories?.get(entry.key) ?: true, enabled = preferences != null && !busy,
                            onCheckedChange = { checked -> val current = preferences ?: return@Switch; scope.launch {
                                busy = true; error = null
                                try { preferences = repository.setPreferences(current.copy(categories = current.categories + (entry.key to checked))) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "Valet kunde inte sparas. Hämta inställningarna igen och försök på nytt." }
                                finally { busy = false }
                            } })
                    }
                }
            }
            item { Text("Viktiga systemmeddelanden visas alltid. Inställningarna gäller notiser inne i appen.", color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
}

@Composable fun NotificationActivityScreen(repository: NotificationCenterRepository, id: String, refreshKey: Int, onBack: () -> Unit) = key(repository.userId, id) {
    var item by remember { mutableStateOf<SharedNotificationActivity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(id, reload, refreshKey, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                try { item = repository.activity(id); error = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { item = null; error = "Passet är inte tillgängligt. Det kan vara privat eller borttaget, eller så saknas anslutning." }
                delay(60_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Till notiser") }; Text("Träningspass", style = MaterialTheme.typography.titleLarge) }
        error?.let { Text(it); TextButton(onClick = { reload++ }) { Text("Försök igen") } }
        if (item == null && error == null) CircularProgressIndicator()
        item?.let { activity ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAvatar(Profile(activity.actorId, activity.name, avatarPath = activity.avatarPath))
                    Column { Text(activity.name, fontWeight = FontWeight.Bold); if (activity.isNpc) Text("Simulerat konto", color = AppColors.Muted) }
                }
                Text(ActivityKind.label(activity.kind), style = MaterialTheme.typography.titleLarge)
                Text(NotificationText.km(activity.distanceMeters), style = MaterialTheme.typography.headlineLarge)
                Text("${activity.durationSeconds / 60} min ${activity.durationSeconds % 60} sek")
                activity.dogName?.let { Text("Med hunden $it") }
                Text("Delad sammanfattning. Rutt och anteckningar visas inte.", color = AppColors.Muted)
            }
        }
    }
}
