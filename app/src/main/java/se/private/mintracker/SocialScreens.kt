package se.oxelv.mintracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.oxelv.mintracker.data.*

@Composable
fun DiscoverScreen(query: String, onQuery: (String) -> Unit, results: List<Profile>, friends: List<FriendListItem>, incoming: List<SocialInboxItem>, sent: List<SocialInboxItem>, actionLoading: Boolean, onSend: (String) -> Unit, loading: Boolean, error: String?, more: Boolean, onMore: () -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Upptäck", style = MaterialTheme.typography.titleLarge)
        SocialTabs(0, onTab)
        OutlinedTextField(query, onQuery, label = { Text("Sök visningsnamn eller @användarnamn") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Endast offentliga profiler visas.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { profile ->
                AppCard(Modifier.fillMaxWidth(), { onOpen(profile.id) }) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { ProfileAvatar(profile); Spacer(Modifier.width(12.dp)); Column { Text(profile.displayName, style = MaterialTheme.typography.titleMedium); profile.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall) }; profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) } } }
                    val relation = when {
                        friends.any { it.userId == profile.id } -> "Vänner"
                        sent.any { it.kind == SocialInboxKind.FRIEND_REQUEST && it.personId == profile.id } -> "Förfrågan skickad"
                        incoming.any { it.kind == SocialInboxKind.FRIEND_REQUEST && it.personId == profile.id } -> "Vill bli vän"
                        else -> null
                    }
                    if (relation == null) TextButton(onClick = { onSend(profile.id) }, enabled = !actionLoading) { Text("Lägg till vän") }
                    else Text(relation, color = AppColors.Blue, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (more) item { TextButton(onClick = onMore, enabled = !loading) { Text("Visa fler") } }
        }
    }
}

@Composable
fun GroupsScreen(groups: List<SocialGroup>, loading: Boolean, error: String?, notice: String?, onBack: () -> Unit, onRefresh: () -> Unit, onCreate: (String, String) -> Unit, onPreview: (String) -> Unit, onOpen: (String) -> Unit, onTab: (Int) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("GROUP") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mina grupper", style = MaterialTheme.typography.titleLarge)
        SocialTabs(2, onTab)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRefresh) { Text("Försök igen") } }
        if (creating) {
            OutlinedTextField(name, { name = it }, label = { Text("Gruppnamn") }, modifier = Modifier.fillMaxWidth())
            Row { FilterChip(type == "GROUP", onClick = { type = "GROUP" }, label = { Text("Grupp") }); Spacer(Modifier.width(8.dp)); FilterChip(type == "FAMILY", onClick = { type = "FAMILY" }, label = { Text("Familj") }) }
            Button(onClick = { onCreate(name, type); creating = false; name = "" }, enabled = !loading && name.isNotBlank()) { Text("Skapa") }
        } else PrimaryActionButton("Skapa grupp eller familj", onClick = { creating = true })
        OutlinedTextField(code, { code = it }, label = { Text("Inbjudningskod") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onPreview(code) }, enabled = !loading && code.isNotBlank()) { Text("Sök grupp") }
        groups.forEach { group -> AppCard(Modifier.fillMaxWidth(), { onOpen(group.id) }) { Text(group.name, style = MaterialTheme.typography.titleMedium); Text(if (group.groupType == "FAMILY") "Familj" else "Grupp", style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
fun JoinGroupScreen(group: GroupPreview, loading: Boolean, error: String?, onBack: () -> Unit, onJoin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SocialHeader("Gå med i grupp", onBack)
        Text(group.name, style = MaterialTheme.typography.headlineSmall)
        Text(if (group.groupType == "FAMILY") "Familj" else "Grupp")
        Text("${group.memberCount} medlemmar")
        if (group.alreadyMember) Text("Du är redan medlem i gruppen.")
        else Text("Gå med i ${if (group.groupType == "FAMILY") "Familjen" else "gruppen"} ${group.name}?")
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row {
            TextButton(onClick = onBack) { Text("Avbryt") }
            if (GroupActionRules.canJoin(group)) Button(onClick = onJoin, enabled = !loading) { Text("Gå med") }
        }
    }
}

@Composable
fun GroupDetailScreen(group: SocialGroup?, members: List<GroupMemberView>, myId: String, loading: Boolean, error: String?, weeklyStats: List<WeeklyGroupStat>, statsLoading: Boolean, statsError: String?, onBack: () -> Unit, onRetry: () -> Unit, onStatsRetry: () -> Unit, onMember: (String) -> Unit, onLeave: () -> Unit, onRemove: (String) -> Unit, onTransfer: (String) -> Unit, onDelete: () -> Unit) {
    var selectedMember by remember(group?.id, myId) { mutableStateOf<GroupMemberView?>(null) }
    var removeTarget by remember(group?.id, myId) { mutableStateOf<GroupMemberView?>(null) }
    var transferPicker by remember(group?.id, myId) { mutableStateOf(false) }
    var transferTarget by remember(group?.id, myId) { mutableStateOf<GroupMemberView?>(null) }
    var confirmLeave by remember(group?.id, myId) { mutableStateOf(false) }
    var deleteStage by remember(group?.id, myId) { mutableIntStateOf(0) }
    var deleteName by remember(group?.id, myId) { mutableStateOf("") }
    var tab by remember(group?.id) { mutableIntStateOf(0) }
    val role = members.firstOrNull { it.member.userId == myId }?.member?.role
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SocialHeader(group?.name ?: "Grupp", onBack)
        SegmentedTabs(listOf("Översikt", "Medlemmar", "Denna vecka"), tab, { tab = it })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Försök igen") } }
        group?.let {
            if (tab == 0) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = AppColors.Navy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(it.name, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
                        Text("${members.size} medlemmar · ${if (it.groupType == "FAMILY") "Familj" else "Grupp"}", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
                AppCard(Modifier.fillMaxWidth()) { Text("Inbjudningskod", style = MaterialTheme.typography.bodySmall); Text(it.inviteCode, style = MaterialTheme.typography.titleMedium) }
            }
            if (tab == 0 || tab == 2) {
            Text("Denna vecka", style = MaterialTheme.typography.titleMedium)
            if (statsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            statsError?.let { message -> Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = onStatsRetry) { Text("Försök igen") } }
            val displayedStats = if (statsError == null && !statsLoading) GroupStatsRules.withInactiveMembers(weeklyStats, members) else emptyList()
            if (!statsLoading && statsError == null && displayedStats.isEmpty()) Text("Ingen statistik att visa denna vecka.")
            (if (tab == 0) displayedStats.take(5) else displayedStats).forEach { stat ->
                AppCard(Modifier.fillMaxWidth()) {
                        members.firstOrNull { it.member.userId == stat.userId }?.profile?.let { ProfileAvatar(it, 44.dp) }
                        Text(stat.displayName, style = MaterialTheme.typography.titleMedium)
                        stat.username?.let { username -> Text("@$username", style = MaterialTheme.typography.bodySmall) }
                        Text(GroupStatsRules.distance(stat), style = MaterialTheme.typography.titleLarge)
                        Text("${GroupStatsRules.activities(stat)} · ${GroupStatsRules.duration(stat)}", color = AppColors.Muted)
                }
            }
            }
            if (tab == 1) {
            Text("Medlemmar", style = MaterialTheme.typography.titleMedium)
            members.forEach { view ->
                Row(Modifier.fillMaxWidth().clickable { selectedMember = view }.padding(12.dp)) {
                    view.profile?.let { ProfileAvatar(it) }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(view.profile?.displayName ?: "Privat profil")
                        view.profile?.username?.let { name -> Text("@$name", style = MaterialTheme.typography.bodySmall) }
                        Text(groupRoleLabel(view.member.role), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            }
            if (tab == 0) {
            if (GroupActionRules.canLeave(role)) TextButton(onClick = { confirmLeave = true }, enabled = !loading) { Text("Lämna grupp") }
            if (GroupActionRules.canManage(role)) {
                TextButton(onClick = { transferPicker = true }, enabled = !loading) { Text("Överför ägarskap") }
                HorizontalDivider()
                Text("Destruktiv åtgärd", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { deleteStage = 1 }, enabled = !loading) { Text("Radera grupp", color = MaterialTheme.colorScheme.error) }
            }
            }
        }
    }
    selectedMember?.let { view ->
        AlertDialog(onDismissRequest = { selectedMember = null }, title = { Text(view.profile?.displayName ?: "Privat profil") }, text = { Text(view.profile?.username?.let { "@$it" } ?: groupRoleLabel(view.member.role)) }, confirmButton = {
            TextButton(onClick = { selectedMember = null; onMember(view.member.userId) }) { Text("Visa profil") }
        }, dismissButton = {
            if (GroupActionRules.canRemove(myId, role, view.member.userId, members.map { it.member })) TextButton(onClick = { selectedMember = null; removeTarget = view }) { Text("Ta bort från grupp") }
            else TextButton(onClick = { selectedMember = null }) { Text("Stäng") }
        })
    }
    removeTarget?.let { view ->
        AlertDialog(onDismissRequest = { removeTarget = null }, title = { Text("Ta bort medlem") }, text = { Text("Ta bort ${view.profile?.displayName ?: "medlemmen"} från gruppen?") }, confirmButton = {
            TextButton(onClick = { removeTarget = null; onRemove(view.member.userId) }, enabled = !loading) { Text("Ta bort") }
        }, dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Avbryt") } })
    }
    if (confirmLeave && group != null) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Lämna grupp") }, text = { Text("Vill du lämna ${group.name}?") }, confirmButton = {
        TextButton(onClick = { confirmLeave = false; onLeave() }, enabled = !loading) { Text("Lämna") }
    }, dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Avbryt") } })
    if (transferPicker && group != null) AlertDialog(onDismissRequest = { transferPicker = false }, title = { Text("Överför ägarskap") }, text = {
        Column { Text("Välj en annan medlem i ${group.name}."); members.filter { GroupActionRules.canTransfer(myId, role, it.member.userId, members.map { member -> member.member }) }.forEach { view ->
            TextButton(onClick = { transferPicker = false; transferTarget = view }) { Text(view.profile?.displayName ?: "Privat profil") }
        } }
    }, confirmButton = { TextButton(onClick = { transferPicker = false }) { Text("Avbryt") } })
    transferTarget?.let { view ->
        AlertDialog(onDismissRequest = { transferTarget = null }, title = { Text("Bekräfta ägarbyte") }, text = { Text("Vill du göra ${view.profile?.displayName ?: "medlemmen"} till ägare av ${group?.name ?: "gruppen"}? Du kommer inte längre vara gruppens ägare.") }, confirmButton = {
            TextButton(onClick = { transferTarget = null; onTransfer(view.member.userId) }, enabled = !loading) { Text("Överför") }
        }, dismissButton = { TextButton(onClick = { transferTarget = null }) { Text("Avbryt") } })
    }
    if (deleteStage == 1 && group != null) AlertDialog(onDismissRequest = { deleteStage = 0 }, title = { Text("Radera grupp") }, text = { Text("Radera ${group.name}? Gruppen och medlemskapen tas bort. Detta går inte att ångra.") }, confirmButton = {
        TextButton(onClick = { deleteStage = 2 }) { Text("Fortsätt") }
    }, dismissButton = { TextButton(onClick = { deleteStage = 0 }) { Text("Avbryt") } })
    if (deleteStage == 2 && group != null) AlertDialog(onDismissRequest = { deleteStage = 0; deleteName = "" }, title = { Text("Bekräfta radering") }, text = {
        Column { Text("Skriv gruppens namn för att bekräfta: ${group.name}"); OutlinedTextField(deleteName, { deleteName = it }, label = { Text("Gruppnamn") }) }
    }, confirmButton = {
        TextButton(onClick = { deleteStage = 0; deleteName = ""; onDelete() }, enabled = !loading && deleteName == group.name) { Text("Radera grupp", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(onClick = { deleteStage = 0; deleteName = "" }) { Text("Avbryt") } })
}

private fun groupRoleLabel(role: String): String = when (role) { "OWNER" -> "Ägare"; "ADMIN" -> "Administratör"; else -> "Medlem" }

@Composable private fun SocialHeader(title: String, onBack: () -> Unit) { Row { TextButton(onClick = onBack) { Text("‹", fontSize = 32.sp) }; Text(title, fontSize = 23.sp, modifier = Modifier.padding(top = 9.dp)) } }

fun socialErrorMessage(error: Exception): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        error is IllegalArgumentException -> error.message ?: "Kontrollera uppgifterna."
        "already_member" in message || "already a member" in message || "redan medlem" in message -> "Du är redan medlem i gruppen."
        "not a member" in message || "not_member" in message || "redan lämnat" in message -> "Du är inte längre medlem i gruppen. Sidan uppdateras."
        "not owner" in message || "owner changed" in message || "not_owner" in message -> "Du är inte längre gruppens ägare. Sidan uppdateras."
        "transfer ownership" in message || "owner must" in message -> "Överför ägarskapet eller radera gruppen innan du lämnar den."
        "invite_code" in message || "invalid code" in message || "ogiltig kod" in message -> "Inbjudningskoden är ogiltig eller har slutat gälla."
        "jwt" in message || "session" in message || "401" in message -> "Din inloggning har gått ut. Logga in igen."
        "not found" in message || "does not exist" in message || "deleted" in message -> "Gruppen eller medlemmen finns inte längre. Sidan uppdateras."
        "stale" in message || "conflict" in message -> "Gruppen har ändrats sedan du öppnade den. Sidan uppdateras."
        "duplicate" in message || "23505" in message -> "Handlingen har redan utförts. Sidan uppdateras."
        "row-level security" in message || "permission denied" in message || "42501" in message -> "Supabase nekade åtkomst. Kontrollera gruppens RLS-regler."
        socialIsNetworkError(error) -> "Kunde inte ansluta. Kontrollera internet och försök igen."
        else -> "Kunde inte läsa eller spara gruppen. Försök igen."
    }
}

fun socialIsNetworkError(error: Exception): Boolean {
    val message = error.message.orEmpty().lowercase()
    return "network" in message || "timeout" in message || "connect" in message || "unreachable" in message
}

fun groupStatsErrorMessage(error: Exception): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        "row-level security" in message || "permission denied" in message || "42501" in message -> "Du har inte behörighet att visa gruppens statistik."
        "network" in message || "timeout" in message || "connect" in message -> "Kunde inte hämta veckostatistiken. Kontrollera internet och försök igen."
        else -> "Kunde inte hämta veckostatistiken. Försök igen."
    }
}
