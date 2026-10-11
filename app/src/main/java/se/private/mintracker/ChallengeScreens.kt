package se.oxelv.mintracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.oxelv.mintracker.data.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Self-contained UI state; the activity supplies navigation and account-scoped dependencies. */
@Composable
fun ChallengeScreen(
    userId: String,
    repository: ChallengeRepository,
    groups: List<SocialGroup>,
    friends: List<FriendListItem>,
    initialChallengeId: String?,
    onInitialConsumed: () -> Unit,
    onProfile: (String) -> Unit,
    onTab: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val syncRevision = socialRefreshRevision(userId)
    var page by remember(userId) { mutableStateOf("list") }
    var selectedId by remember(userId) { mutableStateOf<String?>(null) }
    var list by remember(userId) { mutableStateOf<List<ChallengeSummary>>(emptyList()) }
    var listProgress by remember(userId) { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var detail by remember(userId) { mutableStateOf<ChallengeDetail?>(null) }
    var board by remember(userId) { mutableStateOf<List<ChallengeRank>>(emptyList()) }
    var loading by remember(userId) { mutableStateOf(false) }
    var busy by remember(userId) { mutableStateOf(false) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    var notice by remember(userId) { mutableStateOf<String?>(null) }
    var reload by remember(userId) { mutableIntStateOf(0) }
    var confirm by remember(userId) { mutableStateOf<String?>(null) }
    var createRequiresReview by remember(userId) { mutableStateOf(false) }
    LaunchedEffect(userId, initialChallengeId) {
        if (initialChallengeId != null) { selectedId = initialChallengeId; page = "detail"; onInitialConsumed() }
    }
    LaunchedEffect(userId, page, selectedId, reload, syncRevision) {
        loading = true; error = null
        try {
            if (page == "list") {
                val fresh = repository.mine()
                list = fresh
                createRequiresReview = false // A successful server read resolves an uncertain create response.
                listProgress = fresh.filter { it.joined }.take(20).mapNotNull { challenge ->
                        runCatching { repository.leaderboard(challenge.id).firstOrNull { it.userId == userId }?.progressValue }
                            .getOrNull()?.let { challenge.id to it }
                    }.toMap()
            }
            if (page == "detail" && selectedId != null) {
                detail = repository.detail(selectedId!!)
                board = if (detail?.joined == true || detail?.scope == "GROUP" || detail?.scope == "PRIVATE")
                    repository.leaderboard(selectedId!!) else emptyList()
            }
        } catch (e: Exception) { error = challengeErrorMessage(e) }
        finally { loading = false }
    }
    fun action(label: String, work: suspend () -> Unit, after: () -> Unit = {}) {
        scope.launch {
            busy = true; error = null; notice = null
            try { work(); notice = label; after(); reload++ }
            catch (e: Exception) { error = challengeErrorMessage(e) }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == "list") {
            Text("Utmaningar", style = MaterialTheme.typography.titleLarge)
            SocialTabs(4, onTab)
            PrimaryActionButton("+ Skapa utmaning", enabled = !createRequiresReview, onClick = { page = "create" })
            if (createRequiresReview) Text("Kontrollera listan efter nätverksfelet. Uppdatera den innan du skapar igen.", color = AppColors.Muted)
            TextButton(onClick = { reload++ }) { Text("Uppdatera") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("Försök igen") } }
            notice?.let { Text(it, color = AppColors.Blue) }
            if (!loading && error == null && list.isEmpty()) EmptyState("Du har inga utmaningar ännu. Skapa en eller tacka ja till en inbjudan.")
            listOf("ACTIVE" to "Pågår", "UPCOMING" to "Kommande", "COMPLETED" to "Avslutade", "CANCELLED" to "Avbrutna").forEach { (state, heading) ->
                val rows = list.filter { ChallengeRules.state(it.startAt, it.endAt, status = it.status) == state }
                if (rows.isNotEmpty()) {
                    Text(heading, style = MaterialTheme.typography.titleMedium)
                    rows.forEach { challenge -> AppCard(Modifier.fillMaxWidth(), { selectedId = challenge.id; detail = null; page = "detail" }) {
                        Text(challenge.title, style = MaterialTheme.typography.titleMedium, color = AppColors.Navy)
                        if (challenge.origin == "SYSTEM") Text("Systemutmaning", style = MaterialTheme.typography.labelMedium)
                        Text("${listProgress[challenge.id]?.let { ChallengeRules.format(challenge.metricType, it) + " / " } ?: "Mål: "}${ChallengeRules.format(challenge.metricType, challenge.targetValue)} · ${challenge.participantCount} deltagare")
                        if (challenge.joined && challenge.id in listProgress) LinearProgressIndicator(
                            progress = { (ChallengeRules.percent(listProgress[challenge.id] ?: 0.0, challenge.targetValue) / 100.0).coerceIn(0.0, 1.0).toFloat() },
                            modifier = Modifier.fillMaxWidth())
                        Text(if (challenge.joined) "Du deltar" else "Gå med från detaljvyn", color = AppColors.Muted)
                    } }
                }
            }
        } else if (page == "create") {
            ChallengeCreateForm(groups, friends, busy, createRequiresReview, error, { page = "list" }) { form, selectedFriends ->
                if (!busy && !createRequiresReview) {
                    busy = true; error = null
                    scope.launch {
                    try {
                        val id = repository.create(form)
                        selectedId = id; page = "detail"; notice = "Utmaningen skapades."
                        val failures = selectedFriends.count { friendId -> runCatching { repository.invite(id, friendId) }.isFailure }
                        if (failures > 0) notice = "Utmaningen skapades, men $failures inbjudan kunde inte skickas. Bjud in från detaljvyn."
                        reload++
                    } catch (e: Exception) {
                        se.oxelv.mintracker.data.Diagnostics.report(se.oxelv.mintracker.data.DiagnosticCode.CHALLENGE_CREATE_FAILED, e)
                        createRequiresReview = true
                        error = "Det gick inte att bekräfta resultatet. Kontrollera utmaningslistan innan du försöker skapa igen. ${challengeErrorMessage(e)}"
                    }
                    finally { busy = false }
                    }
                }
            }
        } else {
            ChallengeHeader(detail?.title ?: "Utmaning", { page = "list"; selectedId = null })
            TextButton(onClick = { reload++ }) { Text("Uppdatera resultat") }
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("Försök igen") } }
            notice?.let { Text(it, color = AppColors.Blue) }
            detail?.let { challenge ->
                val status = ChallengeRules.state(challenge.startAt, challenge.endAt, status = challenge.status)
                val myRank = board.firstOrNull { it.userId == userId }
                AppCard(Modifier.fillMaxWidth()) {
                    Text(challenge.title, style = MaterialTheme.typography.headlineSmall, color = AppColors.Navy)
                    challenge.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    val zone = ZoneId.of(challenge.timeZone)
                    val firstDay = java.time.OffsetDateTime.parse(challenge.startAt).atZoneSameInstant(zone).toLocalDate()
                    val lastDay = java.time.OffsetDateTime.parse(challenge.endAt).atZoneSameInstant(zone).toLocalDate().minusDays(1)
                    Text("$firstDay – $lastDay")
                    Text(challenge.activityTypes.joinToString(" · ") { ActivityKind.label(it) }, color = AppColors.Muted)
                    Text("Mål: ${ChallengeRules.format(challenge.metricType, challenge.targetValue)}", fontWeight = FontWeight.Bold)
                    if (challenge.origin == "SYSTEM") Text("Systemutmaning")
                    if (challenge.metricType == "FASTEST_5K") Text("Bästa tid för fem sammanhängande kilometersplits. Pass utan splits räknas inte.")
                    Text(when (status) { "UPCOMING" -> "Kommande"; "COMPLETED" -> "Avslutad"; "CANCELLED" -> "Avbruten"; "ARCHIVED" -> "Arkiverad"; else -> "Pågår" })
                }
                if (challenge.joined) AppCard(Modifier.fillMaxWidth()) {
                    Text(if (myRank?.completed == true) "🏆 UTMANING KLAR!" else "DIN PROGRESS", style = MaterialTheme.typography.titleMedium)
                    Text("${ChallengeRules.format(challenge.metricType, myRank?.progressValue ?: 0.0)} / ${ChallengeRules.format(challenge.metricType, challenge.targetValue)}", style = MaterialTheme.typography.titleLarge)
                    val fraction = ((myRank?.progressPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("${(myRank?.progressPercent ?: 0.0).toInt()} %")
                }
                if (challenge.scope == "GROUP" && !challenge.joined && status in listOf("ACTIVE", "UPCOMING"))
                    Button(onClick = { action("Du deltar nu.", { repository.join(challenge.id) }) }, enabled = !busy) { Text("Gå med") }
                if (challenge.joined && !(challenge.creatorId == userId && challenge.scope != "GROUP"))
                    TextButton(onClick = { confirm = "leave" }, enabled = !busy) { Text("Lämna utmaning") }
                if (challenge.creatorId == userId || (challenge.scope == "GROUP" && groups.any { it.id == challenge.groupId }))
                    TextButton(onClick = { confirm = "delete" }, enabled = !busy) { Text("Radera utmaning") }
                if (challenge.scope == "FRIENDS" && challenge.creatorId == userId && status != "COMPLETED") {
                    Text("Bjud in vänner", style = MaterialTheme.typography.titleMedium)
                    friends.forEach { friend -> TextButton(onClick = { action("Inbjudan skickades.", { repository.invite(challenge.id, friend.userId) }) }, enabled = !busy) {
                        Text("Bjud in ${friend.profile?.displayName ?: "vän"}")
                    } }
                }
                if (challenge.joined || challenge.scope != "FRIENDS") {
                    Text("Topplista", style = MaterialTheme.typography.titleMedium)
                    if (board.isEmpty() && !loading) Text("Inga deltagare ännu.")
                    board.forEach { rank -> AppCard(Modifier.fillMaxWidth(), { onProfile(rank.userId) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProfileAvatar(Profile(rank.userId, rank.displayName, username = rank.username, avatarPath = rank.avatarPath), 36.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("${when (rank.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${rank.rank}." }} ${rank.displayName}")
                            }
                            Text(ChallengeRules.format(challenge.metricType, rank.progressValue), fontWeight = FontWeight.Bold)
                        }
                    } }
                }
            }
        }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (confirm == "delete") "Radera utmaning?" else "Lämna utmaning?") },
        text = { Text("Detta påverkar inte dina sparade träningspass.") },
        confirmButton = { TextButton(onClick = {
            val selected = selectedId; val choice = confirm; confirm = null
            if (selected != null) action(if (choice == "delete") "Utmaningen raderades." else "Du lämnade utmaningen.", {
                if (choice == "delete") repository.delete(selected) else repository.leave(selected)
            }, { if (choice == "delete") { page = "list"; selectedId = null } })
        }) { Text("Bekräfta") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Avbryt") } })
}

@Composable
private fun ChallengeCreateForm(groups: List<SocialGroup>, friends: List<FriendListItem>, busy: Boolean, blocked: Boolean, serverError: String?, onBack: () -> Unit, onCreate: (NewChallenge, Set<String>) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var metric by remember { mutableStateOf("DISTANCE") }
    var target by remember { mutableStateOf("") }
    var types by remember { mutableStateOf(setOf("RUN")) }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf(LocalDate.now().plusDays(30).toString()) }
    var scope by remember { mutableStateOf("PRIVATE") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var invitees by remember { mutableStateOf<Set<String>>(emptySet()) }
    var review by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    ChallengeHeader(if (review) "Granska utmaning" else "Skapa utmaning", { if (review) review = false else onBack() })
    if (!review) {
        OutlinedTextField(title, { title = it }, label = { Text("Namn") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("Beskrivning (valfri)") }, modifier = Modifier.fillMaxWidth())
        Text("Måltyp", style = MaterialTheme.typography.titleMedium)
        listOf("DISTANCE" to "Distans", "ACTIVITY_COUNT" to "Antal pass", "DURATION" to "Tid", "ELEVATION_GAIN" to "Höjdmeter").forEach { (value, label) ->
            FilterChip(metric == value, onClick = { metric = value }, label = { Text(label) })
        }
        OutlinedTextField(target, { target = it }, label = { Text("Mål i ${ChallengeRules.unit(metric)}") }, modifier = Modifier.fillMaxWidth())
        Text("Aktiviteter", style = MaterialTheme.typography.titleMedium)
        ChallengeRules.activityTypes.forEach { type ->
            FilterChip(type in types, onClick = { types = if (type in types) types - type else types + type }, label = { Text(ActivityKind.label(type)) })
        }
        OutlinedTextField(start, { start = it }, label = { Text("Startdatum ÅÅÅÅ-MM-DD") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(end, { end = it }, label = { Text("Slutdatum ÅÅÅÅ-MM-DD") }, modifier = Modifier.fillMaxWidth())
        Text("Typ", style = MaterialTheme.typography.titleMedium)
        listOf("PRIVATE" to "Privat", "FRIENDS" to "Vänner", "GROUP" to "Grupp").forEach { (value, label) ->
            FilterChip(scope == value, onClick = { scope = value }, label = { Text(label) })
        }
        if (scope == "GROUP") {
            Text("Välj grupp", style = MaterialTheme.typography.titleMedium)
            if (groups.isEmpty()) Text("Du behöver vara ägare eller admin i en grupp.")
            groups.forEach { group -> FilterChip(groupId == group.id, onClick = { groupId = group.id }, label = { Text(group.name) }) }
        }
        if (scope == "FRIENDS") {
            Text("Bjud in vänner", style = MaterialTheme.typography.titleMedium)
            friends.forEach { friend -> FilterChip(friend.userId in invitees,
                onClick = { invitees = if (friend.userId in invitees) invitees - friend.userId else invitees + friend.userId },
                label = { Text(friend.profile?.displayName ?: "Vän") }) }
        }
    } else AppCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("Mål: $target ${ChallengeRules.unit(metric)}")
        Text("$start – $end · ${types.joinToString { ActivityKind.label(it) }}")
        Text("${when (scope) { "GROUP" -> "Grupp"; "FRIENDS" -> "Vänner"; else -> "Privat" }} · ${invitees.size} inbjudna")
    }
    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(onClick = {
        val startDate = runCatching { LocalDate.parse(start) }.getOrNull()
        val endDate = runCatching { LocalDate.parse(end) }.getOrNull()
        val number = target.replace(',', '.').toDoubleOrNull()
        if (startDate == null || endDate == null || number == null) { localError = "Ange giltigt mål och datum som ÅÅÅÅ-MM-DD."; return@Button }
        val form = NewChallenge(title, description, metric, number, types, startDate, endDate, ZoneId.systemDefault().id, scope, if (scope == "GROUP") groupId else null)
        localError = ChallengeRules.validate(form)
        if (localError == null) { if (review) onCreate(form, if (scope == "FRIENDS") invitees else emptySet()) else review = true }
    }, enabled = !busy && !blocked) { Text(if (review) "Skapa utmaning" else "Granska") }
}

@Composable
private fun ChallengeHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Tillbaka") }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
