package se.oxelv.mintracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.oxelv.mintracker.data.*
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SocialHubScreen(inbox: List<SocialInboxItem>, sent: List<SocialInboxItem>, challengeInbox: List<ChallengeInvitation>, challengeSent: List<ChallengeInvitation>, loading: Boolean, actionLoading: Boolean, error: String?, onBack: () -> Unit, onRetry: () -> Unit, onProfile: (String) -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit, onCancel: (String) -> Unit, onChallengeAccept: (String) -> Unit, onChallengeDecline: (String) -> Unit, onChallengeCancel: (String) -> Unit, onChallenge: (String) -> Unit, onTab: (Int) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Inkorg", style = MaterialTheme.typography.titleLarge)
        SocialTabs(3, onTab)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Inkorg (${inbox.size + challengeInbox.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Skickat (${sent.size + challengeSent.size})") })
        }
        if (loading || actionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Försök igen") } }
        Text(if (tab == 0) "Väntar på dig" else "Väntar på svar", style = MaterialTheme.typography.titleMedium)
        val visible = if (tab == 0) inbox else sent
        val visibleChallenges = if (tab == 0) challengeInbox else challengeSent
        if (!loading && visible.isEmpty() && visibleChallenges.isEmpty()) Text(if (tab == 0) "Inga väntande förfrågningar." else "Inga skickade förfrågningar väntar på svar.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleChallenges, key = { "challenge:${it.id}" }) { invitation ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("UTMANINGSINBJUDAN", color = AppColors.Blue, style = MaterialTheme.typography.labelLarge)
                    Text("${invitation.displayName ?: "En användare"} · ${invitation.challengeTitle}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onChallenge(invitation.challengeId) }) { Text("Visa utmaning") }
                    if (tab == 0) Row {
                        Button(onClick = { onChallengeAccept(invitation.id) }, enabled = !actionLoading) { Text("Acceptera") }
                        TextButton(onClick = { onChallengeDecline(invitation.id) }, enabled = !actionLoading) { Text("Avböj") }
                    } else TextButton(onClick = { onChallengeCancel(invitation.id) }, enabled = !actionLoading) { Text("Återkalla") }
                } }
            }
            items(visible, key = { "${it.kind}:${it.requestId}" }) { item ->
                FriendRequestCard(item, actionLoading, onProfile, onAccept, onDecline, onCancel)
            }
        }
    }
}

@Composable
private fun FriendRequestCard(item: SocialInboxItem, busy: Boolean, onProfile: (String) -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit, onCancel: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { item.person?.let { ProfileAvatar(it) }; Spacer(Modifier.width(10.dp)); Column {
                Text(item.person?.displayName ?: "Profilen är inte tillgänglig")
                item.person?.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall) }
                Text(friendRequestDate(item.createdAt), style = MaterialTheme.typography.bodySmall)
            } }
            TextButton(onClick = { onProfile(item.personId) }) { Text("Visa profil") }
            if (item.kind == SocialInboxKind.FRIEND_REQUEST) {
                if (item.incoming) Row {
                    Button(onClick = { onAccept(item.requestId) }, enabled = !busy) { Text("Acceptera") }
                    TextButton(onClick = { onDecline(item.requestId) }, enabled = !busy) { Text("Avvisa") }
                } else Row {
                    Text("Väntar på svar", modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { onCancel(item.requestId) }, enabled = !busy) { Text("Återkalla") }
                }
            }
        }
    }
}

@Composable
fun FriendRelationControls(profile: Profile, profileAvailable: Boolean, relation: FriendRelation?, loading: Boolean, error: String?, actionLoading: Boolean, onRetry: () -> Unit, onSend: () -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit, onCancel: (String) -> Unit, onRemove: () -> Unit) {
    var confirmRemove by remember(profile.id) { mutableStateOf(false) }
    if (loading || actionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Läs om relationsstatus") } }
    if (!loading && error == null) when (relation?.kind) {
        FriendRelationKind.SELF -> Unit
        FriendRelationKind.NONE -> if (profileAvailable) Button(onClick = onSend, enabled = !actionLoading) { Text("Lägg till vän") }
        FriendRelationKind.OUTGOING_PENDING -> {
            Text("Förfrågan skickad")
            relation.requestId?.let { requestId -> TextButton(onClick = { onCancel(requestId) }, enabled = !actionLoading) { Text("Återkalla") } }
        }
        FriendRelationKind.INCOMING_PENDING -> {
            Text("Vill bli vän med dig")
            relation.requestId?.let { requestId -> Row {
                Button(onClick = { onAccept(requestId) }, enabled = !actionLoading) { Text("Acceptera") }
                TextButton(onClick = { onDecline(requestId) }, enabled = !actionLoading) { Text("Avvisa") }
            } }
        }
        FriendRelationKind.FRIENDS -> {
            Text("✓ Vänner")
            TextButton(onClick = { confirmRemove = true }, enabled = !actionLoading) { Text("Ta bort vän") }
        }
        null -> Unit
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Ta bort vän") }, text = { Text("Ta bort ${profile.displayName} som vän?") }, confirmButton = {
        TextButton(onClick = { confirmRemove = false; onRemove() }, enabled = !actionLoading) { Text("Ta bort") }
    }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Avbryt") } })
}

@Composable
fun FriendsScreen(friends: List<FriendListItem>, loading: Boolean, error: String?, onBack: () -> Unit, onRetry: () -> Unit, onProfile: (String) -> Unit, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Vänner", style = MaterialTheme.typography.titleLarge)
        SocialTabs(1, onTab)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Försök igen") } }
        if (!loading && friends.isEmpty()) Text("Du har inga vänner ännu.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(friends, key = { it.userId }) { friend ->
                Card(onClick = { onProfile(friend.userId) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp)) { friend.profile?.let { ProfileAvatar(it) }; Spacer(Modifier.width(10.dp)); Column {
                        Text(friend.profile?.displayName ?: "Profilen är inte tillgänglig")
                        friend.profile?.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall) }
                    } }
                }
            }
        }
    }
}

private fun friendRequestDate(value: String?): String {
    if (value == null) return "Okänd tid"
    return runCatching { OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale("sv", "SE"))) }
        .getOrDefault(value.substringBefore('T'))
}

fun friendErrorMessage(error: Exception): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        error is IllegalArgumentException -> error.message ?: "Kontrollera uppgifterna."
        "jwt" in message || "session" in message || "401" in message -> "Din inloggning har gått ut. Logga in igen."
        "expired" in message || "cancelled" in message || "declined" in message || "already accepted" in message -> "Förfrågan har ändrats eller gått ut. Listan uppdateras."
        "already friends" in message || "already exists" in message || "23505" in message -> "Ni är redan vänner eller har redan en väntande förfrågan. Sidan uppdateras."
        "not found" in message || "not friends" in message -> "Förfrågan, vänrelationen eller profilen finns inte längre. Sidan uppdateras."
        "network" in message || "timeout" in message || "connect" in message -> "Kunde inte ansluta. Kontrollera internet och försök igen."
        "row-level security" in message || "permission denied" in message || "42501" in message -> "Åtkomst nekades av Supabase. Försök logga in igen."
        else -> "Det gick inte att slutföra vänhandlingen. Försök igen."
    }
}
