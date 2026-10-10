package se.oxelv.mintracker
import io.github.jan.supabase.auth.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import se.oxelv.mintracker.data.ActivityEntity
import se.oxelv.mintracker.data.ActivitySummary
import se.oxelv.mintracker.data.Profile
import se.oxelv.mintracker.data.SocialRules
import se.oxelv.mintracker.data.FriendRelation
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ProfileScreen(profile: Profile?, email: String, activities: List<ActivityEntity>, loading: Boolean, error: String?, onBack: () -> Unit, onSettings: () -> Unit, onDogs: () -> Unit, onRetry: () -> Unit, onSave: (String, String, String, Boolean) -> Unit, onSaveAvatar: (Uri) -> Unit, onRemoveAvatar: () -> Unit, isAdmin: Boolean = false, onAdmin: () -> Unit = {}, onNotifications: () -> Unit = {}) {
    var chosenPhoto by remember(profile?.id) { mutableStateOf<Uri?>(null) }
    var confirmRemoveAvatar by remember(profile?.id) { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { chosenPhoto = it }
    var name by remember(profile?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var username by remember(profile?.id) { mutableStateOf(profile?.username.orEmpty()) }
    var bio by remember(profile?.id) { mutableStateOf(profile?.bio.orEmpty()) }
    var isPublic by remember(profile?.id) { mutableStateOf(profile?.isPublic ?: false) }
    var editing by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile) {
        if (profile != null) {
            if (editing && profile.displayName == name.trim() && profile.username == SocialRules.username(username) && profile.bio.orEmpty() == bio.trim() && profile.isPublic == isPublic) editing = false
            if (!editing) { name = profile.displayName; username = profile.username.orEmpty(); bio = profile.bio.orEmpty(); isPublic = profile.isPublic }
        }
    }
    val totals = remember(activities) { ActivitySummary.totals(activities) }
    val week = remember(activities) { ActivitySummary.week(activities) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Min profil", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Inställningar") }
        }
        if (loading && profile == null) CircularProgressIndicator()
        TextButton(onClick = onNotifications) { Text("Notiser") }
        if (isAdmin) Button(onClick = onAdmin) { Text("Admin") }
        profile?.let {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.clickable(enabled = !loading) { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { ProfileAvatar(it) }
                TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !loading) { Text(if (it.avatarPath == null && it.avatarUrl == null) "Välj profilbild" else "Byt profilbild") }
                if (it.avatarPath != null || it.avatarUrl != null) TextButton(onClick = { confirmRemoveAvatar = true }, enabled = !loading) { Text("Ta bort profilbild") }
                Text(it.displayName, style = MaterialTheme.typography.headlineSmall)
                it.username?.let { username -> Text("@$username", color = AppColors.Muted) }
                it.bio?.takeIf { bio -> bio.isNotBlank() }?.let { bio -> Text(bio, color = AppColors.Muted) }
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Stäng redigering" else "Redigera profil") }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${totals.count}", style = MaterialTheme.typography.titleLarge); Text("Pass", color = AppColors.Muted) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${"%.1f".format(Locale("sv", "SE"), totals.distanceMeters / 1000)}", style = MaterialTheme.typography.titleLarge); Text("Kilometer", color = AppColors.Muted) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${totals.durationMillis / 3_600_000}", style = MaterialTheme.typography.titleLarge); Text("Timmar", color = AppColors.Muted) }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Text("Statistik denna vecka", style = MaterialTheme.typography.titleMedium)
                Text("${"%.1f".format(Locale("sv", "SE"), week.totals.distanceMeters / 1000)} km", style = MaterialTheme.typography.titleLarge)
                Text("${week.totals.count} pass · ${week.totals.durationMillis / 60000} min", color = AppColors.Muted)
                WeeklyChart(week.dailyDistanceMeters)
            }
            if (editing) {
                OutlinedTextField(name, { value -> name = value; validationError = null }, label = { Text("Visningsnamn") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { value -> username = value; validationError = null }, label = { Text("Användarnamn") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("3–30 tecken: bokstäver, siffror, . och _") })
                OutlinedTextField(bio, { value -> if (value.length <= 300) bio = value; validationError = null }, label = { Text("Om mig") }, minLines = 3, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${bio.length}/300") })
                Row { Switch(checked = isPublic, onCheckedChange = { isPublic = it }); Text("Visa min profil för andra användare", Modifier.padding(12.dp)) }
                Text("Offentliga profiler kan hittas i sökningen.", style = MaterialTheme.typography.bodySmall)
                Row {
                    Button(onClick = { validationError = if (name.isBlank()) "Ange ett visningsnamn." else SocialRules.usernameError(username) ?: SocialRules.bioError(bio); if (validationError == null) onSave(name, username, bio, isPublic) }, enabled = !loading) { Text("Spara") }
                    TextButton(onClick = { editing = false; validationError = null }, enabled = !loading) { Text("Avbryt") }
                }
            } else {
                AppCard(Modifier.fillMaxWidth()) { ProfileValue("Synlighet", if (it.isPublic) "Offentlig" else "Privat") }
            }
            AppCard(Modifier.fillMaxWidth()) { ProfileValue("E-post", email); ProfileValue("Profil skapad", formatProfileDate(it.createdAt)) }
            AppCard(Modifier.fillMaxWidth(), onDogs) { Text("Mina hundar", style = MaterialTheme.typography.titleMedium); Text("Lägg till, redigera och arkivera hundar", color = AppColors.Muted) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry, enabled = !loading) { Text("Försök igen") } }
    }
    chosenPhoto?.let { uri -> AlertDialog(onDismissRequest = { chosenPhoto = null }, title = { Text("Förhandsgranska profilbild") }, text = { AsyncImage(model = uri, contentDescription = "Vald profilbild", modifier = Modifier.size(180.dp)) }, confirmButton = { TextButton(onClick = { chosenPhoto = null; onSaveAvatar(uri) }) { Text("Använd bild") } }, dismissButton = { TextButton(onClick = { chosenPhoto = null }) { Text("Avbryt") } }) }
    if (confirmRemoveAvatar) AlertDialog(onDismissRequest = { confirmRemoveAvatar = false }, title = { Text("Ta bort profilbild?") }, text = { Text("Din profil visas därefter med dina initialer.") }, confirmButton = { TextButton(onClick = { confirmRemoveAvatar = false; onRemoveAvatar() }) { Text("Ta bort") } }, dismissButton = { TextButton(onClick = { confirmRemoveAvatar = false }) { Text("Avbryt") } })
}

@Composable
fun PublicProfileScreen(profileId: String?, profile: Profile?, loading: Boolean, error: String?, relation: FriendRelation?, relationLoading: Boolean, relationError: String?, actionLoading: Boolean, onBack: () -> Unit, onRetry: () -> Unit, onRelationRetry: () -> Unit, onSend: () -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit, onCancel: (String) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row { TextButton(onClick = onBack) { Text("‹", fontSize = 32.sp) }; Text("Profil", fontSize = 23.sp, modifier = Modifier.padding(top = 9.dp)) }
        if (loading) CircularProgressIndicator()
        if (!loading && profile == null && error == null) Text("Profilen är privat eller finns inte.")
        profile?.let {
            ProfileAvatar(it)
            ProfileValue("Visningsnamn", it.displayName)
            it.username?.let { value -> ProfileValue("Användarnamn", "@$value") }
            it.bio?.let { value -> ProfileValue("Om mig", value) }
            ProfileValue("Medlem sedan", formatProfileDate(it.createdAt))
            se.oxelv.mintracker.data.supabase.auth.currentUserOrNull()?.id?.let { viewer -> PublicProfileStatistics(viewer, it.id) }
            Text("Grupper · Utmärkelser · Utmaningar", style = MaterialTheme.typography.titleMedium); Text("Kommer senare")
        }
        if (profileId != null) FriendRelationControls(profile ?: Profile(profileId, "Privat profil"), profile != null, relation, relationLoading, relationError, actionLoading, onRelationRetry, onSend, onAccept, onDecline, onCancel, onRemove)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Försök igen") } }
    }
}

@Composable private fun ProfileValue(label: String, value: String) { Column { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); Text(value, fontSize = 19.sp) } }
private fun formatProfileDate(value: String?): String { if (value == null) return "Okänt datum"; return runCatching { OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("sv", "SE"))) }.getOrDefault(value.substringBefore('T')) }

fun profileErrorMessage(error: Exception): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        error is IllegalArgumentException -> error.message ?: "Kontrollera uppgifterna."
        "username" in message && ("unique" in message || "duplicate" in message || "23505" in message) -> "Användarnamnet är redan upptaget."
        "row-level security" in message || "permission denied" in message || "42501" in message -> "Åtkomst nekades av Supabase. Kontrollera profilens RLS-regler."
        "network" in message || "timeout" in message || "connect" in message -> "Kunde inte ansluta till Supabase. Kontrollera internet och försök igen."
        else -> "Profilen kunde inte hämtas eller sparas. Försök igen."
    }
}
