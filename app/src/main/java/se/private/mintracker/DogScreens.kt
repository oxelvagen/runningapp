package se.oxelv.mintracker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.oxelv.mintracker.data.DogEntity
import se.oxelv.mintracker.data.SyncStatus

@Composable
fun DogManagementScreen(
    dogs: List<DogEntity>, busy: Boolean, error: String?, onBack: () -> Unit,
    onCreate: (String, String?, String?) -> Unit,
    onEdit: (String, String, String?, String?) -> Unit,
    onArchive: (String) -> Unit,
    onRetrySync: () -> Unit,
    onSaveAvatar: (String, Uri) -> Unit,
    onRemoveAvatar: (String) -> Unit
) {
    var editing by remember { mutableStateOf<DogEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var archiveTarget by remember { mutableStateOf<DogEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var breed by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var photoDog by remember { mutableStateOf<DogEntity?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var removePhotoDog by remember { mutableStateOf<DogEntity?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photoUri = it }
    LaunchedEffect(dogs) { editing?.let { selected -> editing = dogs.firstOrNull { it.id == selected.id } } }

    fun reset() { editing = null; creating = false; name = ""; breed = ""; birthDate = "" }
    fun open(dog: DogEntity?) {
        editing = dog; creating = true
        name = dog?.name.orEmpty(); breed = dog?.breed.orEmpty(); birthDate = dog?.birthDate.orEmpty()
    }
    LaunchedEffect(busy, error, submitted) {
        if (submitted && !busy) {
            if (error == null) reset()
            submitted = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Profil") }
            Spacer(Modifier.weight(1f))
            Text("Mina hundar", style = MaterialTheme.typography.titleLarge)
        }
        error?.let { ErrorState(it) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (creating) AppCard(Modifier.fillMaxWidth()) {
            Text(if (editing == null) "Ny hund" else "Redigera hund", style = MaterialTheme.typography.titleMedium)
            editing?.let { dog ->
                Box(Modifier.clickable(enabled = !busy) { photoDog = dog; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { DogAvatar(dog) }
                TextButton(onClick = { photoDog = dog; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) { Text(if (dog.avatarPath == null) "Välj hundbild" else "Byt hundbild") }
                if (dog.avatarPath != null) TextButton(onClick = { removePhotoDog = dog }, enabled = !busy) { Text("Ta bort hundbild") }
                if (dog.syncStatus != SyncStatus.SYNCED) Text("Hunden synkas före bilduppladdningen.", color = AppColors.Muted)
            }
            OutlinedTextField(name, { name = it }, label = { Text("Namn") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(breed, { breed = it }, label = { Text("Ras (valfritt)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(birthDate, { birthDate = it }, label = { Text("Födelsedatum ÅÅÅÅ-MM-DD (valfritt)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PrimaryActionButton("SPARA HUND", enabled = !busy && !submitted && name.isNotBlank(), onClick = {
                submitted = true
                val dog = editing
                if (dog == null) onCreate(name, breed.ifBlank { null }, birthDate.ifBlank { null })
                else onEdit(dog.id, name, breed.ifBlank { null }, birthDate.ifBlank { null })
            })
            TextButton(onClick = { reset() }) { Text("Avbryt") }
        } else PrimaryActionButton("LÄGG TILL HUND", onClick = { open(null) })

        val active = dogs.filter { it.archivedAt == null }
        val archived = dogs.filter { it.archivedAt != null }
        if (dogs.isEmpty()) AppCard(Modifier.fillMaxWidth()) { EmptyState("Du har inte lagt till någon hund ännu.") }
        if (active.isNotEmpty()) SectionHeader("Aktiva hundar")
        active.forEach { dog -> AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) { DogAvatar(dog); Spacer(Modifier.width(12.dp)); Text(dog.name, style = MaterialTheme.typography.titleMedium) }
            dog.breed?.let { Text(it, color = AppColors.Muted) }
            Text(dogSyncLabel(dog.syncStatus), color = AppColors.Muted)
            Row { TextButton(onClick = { open(dog) }) { Text("Redigera") }; TextButton(onClick = { photoDog = dog; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) { Text(if (dog.avatarPath == null) "Välj bild" else "Byt bild") }; TextButton(onClick = { archiveTarget = dog }) { Text("Arkivera") } }
        } }
        if (archived.isNotEmpty()) SectionHeader("Arkiverade hundar")
        archived.forEach { dog -> AppCard(Modifier.fillMaxWidth()) {
            Text(dog.name, style = MaterialTheme.typography.titleMedium)
            Text("Arkiverad · finns kvar i tidigare pass", color = AppColors.Muted)
            Text(dogSyncLabel(dog.syncStatus), color = AppColors.Muted)
        } }
        if (dogs.any { it.syncStatus != SyncStatus.SYNCED }) TextButton(onClick = onRetrySync) { Text("Försök synka hundar") }
    }
    archiveTarget?.let { dog -> AlertDialog(
        onDismissRequest = { archiveTarget = null },
        title = { Text("Arkivera ${dog.name}?") },
        text = { Text("Hunden kan inte väljas för nya pass, men finns kvar i historiken.") },
        confirmButton = { TextButton(onClick = { archiveTarget = null; onArchive(dog.id) }, enabled = !busy) { Text("Arkivera") } },
        dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("Avbryt") } }
    ) }
    photoUri?.let { uri -> photoDog?.let { dog -> AlertDialog(onDismissRequest = { photoUri = null; photoDog = null }, title = { Text("Förhandsgranska bild för ${dog.name}") }, text = { AsyncImage(model = uri, contentDescription = "Vald hundbild", modifier = Modifier.size(180.dp)) }, confirmButton = { TextButton(onClick = { photoUri = null; photoDog = null; onSaveAvatar(dog.id, uri) }) { Text("Använd bild") } }, dismissButton = { TextButton(onClick = { photoUri = null; photoDog = null }) { Text("Avbryt") } }) } }
    removePhotoDog?.let { dog -> AlertDialog(onDismissRequest = { removePhotoDog = null }, title = { Text("Ta bort bilden för ${dog.name}?") }, text = { Text("En neutral hundikon visas istället.") }, confirmButton = { TextButton(onClick = { removePhotoDog = null; onRemoveAvatar(dog.id) }) { Text("Ta bort") } }, dismissButton = { TextButton(onClick = { removePhotoDog = null }) { Text("Avbryt") } }) }
}

private fun dogSyncLabel(status: String): String = when (status) {
    SyncStatus.SYNCED -> "Synkroniserad"
    SyncStatus.ERROR -> "Synkfel – väntar på nytt försök"
    else -> "Väntar på synk"
}
