package se.oxelv.mintracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import se.oxelv.mintracker.data.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class AdminDialog(val title: String, val fields: List<AdminField> = emptyList(),
    val initial: JsonObject = buildJsonObject {}, val destructive: Boolean = false,
    val submit: suspend (JsonObject) -> Unit)

@Composable
fun AdminScreen(onBack: () -> Unit) {
    val repository = remember { AdminRepository() }
    val scope = rememberCoroutineScope()
    var authorized by remember { mutableStateOf<Boolean?>(null) }
    var section by remember { mutableStateOf("Dashboard") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    var page by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dashboard by remember { mutableStateOf<JsonObject?>(null) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<List<JsonObject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<AdminDialog?>(null) }

    fun action(success: String, work: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null; notice = null
            try { work(); notice = success; reload++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Åtgärden kunde inte slutföras. Kontrollera behörighet och uppgifter och försök igen." }
            finally { busy = false }
        }
    }
    fun confirm(title: String, destructive: Boolean = false, fields: List<AdminField> = emptyList(), initial: JsonObject = buildJsonObject {}, work: suspend (JsonObject) -> Unit) {
        dialog = AdminDialog(title, fields, initial, destructive, work)
    }
    LaunchedEffect(Unit) {
        try { authorized = repository.isAdmin() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { authorized = false; error = "Adminbehörighet kunde inte verifieras." }
    }
    LaunchedEffect(authorized, section, page, query, filter, reload) {
        if (authorized != true) return@LaunchedEffect
        loading = true; error = null; rows = emptyList(); dashboard = null
        try {
            delay(300)
            if (section == "Dashboard") dashboard = repository.dashboard()
            else if (section != "Notifications") rows = repository.page(section, page, query, filter)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Data kunde inte hämtas. Försök igen." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (detail != null) { detail = null; preview = null } else onBack() }) { Text("‹ Tillbaka") }
            Text("Admin", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { reload++ }, enabled = !busy) { Text("Uppdatera") }
        }
        if (authorized == null) { CircularProgressIndicator(); return@Column }
        if (authorized != true) { Text(error ?: "Åtkomst nekad. Adminbehörighet krävs."); return@Column }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Dashboard", "Users", "NPC", "Challenges", "Notifications", "System", "Flags", "Logs", "Simulation").forEach { tab ->
                FilterChip(section == tab, { section = tab; query = ""; filter = "ALL"; page = 0; detail = null; preview = null; selected = emptySet(); notice = null }, label = { Text(tab) })
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val current = detail
        if (current != null) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(current.text("display_name").ifBlank { current.text("title") }, style = MaterialTheme.typography.titleLarge) }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (section) {
                            "Users" -> {
                                Button(onClick = { confirm("Redigera användare", fields = userFields, initial = current) { repository.command("USER_EDIT", current.text("id"), it); detail = null } }, enabled = !busy) { Text("Redigera") }
                                listOf("BAN" to "Banna", "UNBAN" to "Avbanna", "PASSWORD_RESET" to "Skicka lösenordsåterställning", "SET_PASSWORD" to "Sätt nytt lösenord", "UPDATE_EMAIL" to "Ändra e-post", "DELETE" to "Radera användare").forEach { (code, label) ->
                                    OutlinedButton(onClick = {
                                        val fields = when (code) { "SET_PASSWORD" -> listOf(AdminField("password", "Nytt lösenord (12–128 tecken)", "password")); "UPDATE_EMAIL" -> listOf(AdminField("email", "Ny e-post")); else -> emptyList() }
                                        confirm(label, code == "DELETE", fields) { repository.account(code, current.text("id"), it); detail = null }
                                    }, enabled = !busy) { Text(label) }
                                }
                                OutlinedButton(onClick = { confirm("Arkivera användare") { repository.command("USER_ARCHIVE", current.text("id")); detail = null } }, enabled = !busy) { Text("Arkivera") }
                            }
                            "NPC" -> {
                                Button(onClick = { confirm("Redigera NPC", fields = npcFields, initial = current) { repository.command("NPC_EDIT", current.text("id"), it); detail = null } }, enabled = !busy) { Text("Redigera") }
                                OutlinedButton(onClick = { action("Förhandsgranskning klar. Inga aktiviteter sparades.") { preview = repository.preview(current.text("id")) } }, enabled = !busy) { Text("Förhandsgranska 7 dagar") }
                                OutlinedButton(onClick = { confirm("Simulera fram till aktuell tid") { val result = repository.simulate(current.text("id")); check(!result.flag("busy") && result.text("errors") == "0"); detail = null } }, enabled = !busy) { Text("Simulera") }
                                OutlinedButton(onClick = { confirm("Återställ kondition och motivation") { repository.command("NPC_RESET", current.text("id")); detail = null } }, enabled = !busy) { Text("Återställ tillstånd") }
                                OutlinedButton(onClick = { confirm("Arkivera NPC") { repository.command("NPC_BULK", data = buildJsonObject { putJsonArray("ids") { add(current.text("id")) }; put("archive", true) }); detail = null } }, enabled = !busy) { Text("Arkivera") }
                                OutlinedButton(onClick = { confirm("Radera NPC", true) { repository.account("DELETE_NPC", current.text("profile_id")); detail = null } }, enabled = !busy) { Text("Radera") }
                            }
                            "Challenges" -> listOf("CHALLENGE_CANCEL" to "Avbryt", "CHALLENGE_ARCHIVE" to "Arkivera", "CHALLENGE_DELETE" to "Radera ogiltig utmaning").forEach { (code, label) ->
                                OutlinedButton(onClick = { confirm(label, code == "CHALLENGE_DELETE") { repository.command(code, current.text("id"), buildJsonObject { put("confirmation", "DELETE") }); detail = null } }, enabled = !busy) { Text(label) }
                            }
                        }
                    }
                }
                preview?.let { days ->
                    item { Text("Prognos — sparas inte. Framtida inställningar kan ändra utfallet.", style = MaterialTheme.typography.titleMedium) }
                    items(days) { day -> AppCard(Modifier.fillMaxWidth()) {
                        Text(formatAdminTime(day.text("started_at")))
                        Text(if (day.flag("active")) "${day.text("activity_type")} · ${"%.1f".format(day.text("distance_meters").toDoubleOrNull()?.div(1000) ?: 0.0)} km" else "Vila")
                        Text("${day.text("period")} · Trötthet ${day.text("current_fatigue")}")
                    } }
                }
                items(current.entries.toList(), key = { it.key }) { (key, value) -> AdminValue(key, value) }
            }
        } else if (section == "Notifications") {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Skicka ett systemmeddelande", style = MaterialTheme.typography.titleLarge)
                Text("Meddelandet sparas i användarnas notisinkorg.")
                Button(onClick = { confirm("Skicka systemmeddelande", fields = listOf(
                    AdminField("title", "Rubrik"), AdminField("body", "Meddelande"),
                    AdminField("target", "Mottagare", "enum", listOf("ALL", "ACTIVE", "SELECTED")), AdminField("user_ids", "Valda användar-ID:n, kommaseparerade", "choices")
                ), initial = buildJsonObject { put("target", "ACTIVE"); putJsonArray("user_ids") {} }) { repository.command("NOTIFY", data = it) } }, enabled = !busy) { Text("Skapa meddelande") }
            }
        } else {
            if (section in listOf("Users", "NPC", "Challenges", "Logs")) {
                OutlinedTextField(query, { query = it; page = 0 }, label = { Text("Sök på servern") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val filters = when (section) {
                    "Users" -> listOf("ALL", "ACTIVE", "INACTIVE", "BANNED", "ADMIN")
                    "NPC" -> listOf("ALL", "ENABLED", "DISABLED", "ARCHIVED")
                    "Challenges" -> listOf("ALL", "ACTIVE", "UPCOMING", "COMPLETED", "CANCELLED", "ARCHIVED", "USER", "SYSTEM", "NPC")
                    else -> listOf("ALL", "USER", "NPC", "CHALLENGE", "SYSTEM", "SIMULATION_ERROR")
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { value -> FilterChip(filter == value, { filter = value; page = 0 }, label = { Text(value) }) }
                }
            }
            if (section == "NPC") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirm("Skapa NPC automatiskt") { repository.account("CREATE_NPC", null) } }, enabled = !busy) { Text("Autogenerera NPC") }
                OutlinedButton(onClick = {
                    val initial = buildJsonObject {
                        put("display_name", ""); put("username", ""); put("enabled", true); put("personality", "CONSISTENT_RUNNER")
                        put("fitness_level", 40); put("base_pace_sec_per_km", 360); put("distance_min_km", 3); put("distance_max_km", 8)
                        put("runs_per_week_min", 2); put("runs_per_week_max", 4); put("activity_multiplier", 1); put("motivation", 0.75)
                        put("competitiveness", 0.5); put("consistency", 0.8); put("fatigue_sensitivity", 0.7); put("training_hour", 18)
                        putJsonArray("preferred_days") { add(1); add(3); add(5); add(7) }; put("time_zone", "Europe/Stockholm")
                        putJsonArray("activity_types") { add("RUN") }; put("challenge_enabled", true); put("notifications_enabled", true)
                    }
                    confirm("Skapa NPC manuellt", fields = npcFields, initial = initial) { repository.account("CREATE_NPC", null, buildJsonObject { put("data", it) }) }
                }, enabled = !busy) { Text("Skapa manuellt") }
                OutlinedButton(onClick = { section = "System"; page = 0 }, enabled = !busy) { Text("NPC-inställningar") }
                OutlinedButton(onClick = { confirm("Kör global simulering") { val result = repository.simulate(); check(!result.flag("busy") && result.text("errors") == "0") } }, enabled = !busy) { Text("Simulera alla") }
            }
            if (section == "NPC" && selected.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selected.size} valda", Modifier.padding(12.dp))
                    listOf("enabled" to "Aktivera", "disabled" to "Inaktivera", "challenge_on" to "Utmaningar på", "challenge_off" to "Utmaningar av", "archive" to "Arkivera").forEach { (code, label) ->
                        OutlinedButton(onClick = { confirm("$label valda NPC") {
                            repository.command("NPC_BULK", data = buildJsonObject {
                                putJsonArray("ids") { selected.forEach { add(it) } }
                                when (code) { "enabled" -> put("enabled", true); "disabled" -> put("enabled", false); "challenge_on" -> put("challenge_enabled", true); "challenge_off" -> put("challenge_enabled", false); else -> put("archive", true) }
                            }); selected = emptySet()
                        } }, enabled = !busy) { Text(label) }
                    }
                    OutlinedButton(onClick = { confirm("Ändra aktivitetsnivå för valda NPC", fields = listOf(AdminField("activity_multiplier", "Aktivitetsnivå", "slider")), initial = buildJsonObject { put("activity_multiplier", 1) }) { payload ->
                        repository.command("NPC_BULK", data = buildJsonObject { putJsonArray("ids") { selected.forEach { add(it) } }; put("activity_multiplier", payload.getValue("activity_multiplier")) }); selected = emptySet()
                    } }, enabled = !busy) { Text("Aktivitetsnivå") }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                dashboard?.let { data -> items(data.entries.toList(), key = { it.key }) { (key, value) -> AdminValue(key, value) } }
                if (!loading && rows.isEmpty() && section != "Dashboard") item { Text("Inga poster.") }
                items(rows, key = { it.text("id").ifBlank { it.text("key").ifBlank { "settings" } } }) { row ->
                    AppCard(Modifier.fillMaxWidth()) {
                        when (section) {
                            "Users", "NPC", "Challenges" -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.text("display_name").ifBlank { row.text("title") }, style = MaterialTheme.typography.titleMedium)
                                        Text(if (section == "Challenges") "${row.text("challenge_origin")} · ${row.text("status")} · ${row.text("participants")} deltagare" else "@${row.text("username")} ${row.text("email")}")
                                        if (section == "NPC") Text("${row.text("actual_frequency")} pass/vecka · ${row.text("actual_weekly_km")} km/vecka\nKondition ${row.text("current_fitness")} · Trötthet ${row.text("current_fatigue")}\nAktiv: ${row.flag("enabled")} · Utmaningar: ${row.flag("challenge_enabled")}")
                                        if (section == "Users") Text("${row.text("role")} · ${if (row.flag("banned")) "Bannad" else "Aktiv"} · ${row.text("activities")} pass")
                                    }
                                    if (section == "NPC") Checkbox(row.text("id") in selected, { checked -> selected = if (checked) selected + row.text("id") else selected - row.text("id") }, enabled = !busy)
                                }
                                TextButton(onClick = { action("Detaljer hämtade") { detail = if (section == "NPC") row else repository.detail(section, row.text("id")) } }, enabled = !busy) { Text("Öppna detaljer") }
                            }
                            "System" -> {
                                Text("System och NPC-inställningar", style = MaterialTheme.typography.titleLarge)
                                Text("Aktivitetsnivån styr hur ofta NPC tränar. 0 = inga pass, 1 = normal, 2 = mycket hög.")
                                Text("NPC-system: ${if (row.flag("npc_system_enabled")) "På" else "Av"} · Global aktivitet ${row.text("npc_global_activity_multiplier")}")
                                Text("Systemutmaningar: ${if (row.flag("system_challenges_enabled")) "På" else "Av"}")
                                Button(onClick = { confirm("Spara systeminställningar", fields = systemFields, initial = row) { repository.command("SETTINGS", data = it) } }, enabled = !busy) { Text("Redigera inställningar") }
                            }
                            "Flags" -> {
                                Text(row.text("key"), style = MaterialTheme.typography.titleMedium)
                                Switch(row.flag("enabled"), { checked -> confirm("Ändra funktionsflagga ${row.text("key")}") { repository.command("FLAG", data = buildJsonObject { put("key", row.text("key")); put("enabled", checked) }) } }, enabled = !busy)
                            }
                            else -> row.forEach { (key, value) -> AdminValue(key, value, false) }
                        }
                    }
                }
            }
            if (section in listOf("Users", "NPC", "Challenges", "Logs", "Simulation")) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 0 && !loading && !busy) { Text("Föregående") }
                Text("Sida ${page + 1}", Modifier.padding(12.dp))
                TextButton(onClick = { page++ }, enabled = rows.size == 25 && !loading && !busy) { Text("Nästa") }
            }
        }
    }
    dialog?.let { value -> AdminEditDialog(value, { dialog = null }) { payload ->
        dialog = null
        action("${value.title} — klart.") { value.submit(payload) }
    } }
}

@Composable
private fun AdminEditDialog(dialog: AdminDialog, onDismiss: () -> Unit, onSubmit: (JsonObject) -> Unit) {
    val values = remember(dialog) { mutableStateMapOf<String, String>().apply {
        dialog.fields.forEach { field ->
            val value = dialog.initial[field.key]
            put(field.key, if (value is JsonArray) value.joinToString(",") { it.jsonPrimitive.content } else (value as? JsonPrimitive)?.contentOrNull ?: if (field.kind == "boolean") "false" else "")
        }
    } }
    var confirmation by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(dialog.title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (dialog.destructive) {
                Text("Detta tar permanent bort posten och dess tillhörande data. För en användare tas kontot bort. Åtgärden kan inte ångras.")
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Skriv DELETE för att bekräfta") }, singleLine = true)
            } else Text("Bekräfta att du vill genomföra åtgärden.")
            dialog.fields.forEach { field ->
                val value = values[field.key].orEmpty()
                when (field.kind) {
                    "boolean" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(field.label, Modifier.weight(1f)); Switch(value == "true", { values[field.key] = it.toString() }) }
                    "slider" -> Column { Text("${field.label}: ${"%.2f".format(value.toFloatOrNull() ?: 1f)}"); Slider(value.toFloatOrNull() ?: 1f, { values[field.key] = it.toString() }, valueRange = 0f..2f) }
                    "enum" -> Column {
                        Text(field.label)
                        field.options.forEach { option -> Row(Modifier.fillMaxWidth().clickable { values[field.key] = option }) { RadioButton(value == option, { values[field.key] = option }); Text(option, Modifier.padding(top = 12.dp)) } }
                    }
                    "choices" -> if (field.options.isNotEmpty()) Column {
                        Text(field.label)
                        field.options.forEach { option ->
                            val chosen = value.split(',').filter { it.isNotBlank() }.toSet()
                            Row { Checkbox(option in chosen, { checked -> values[field.key] = (if (checked) chosen + option else chosen - option).joinToString(",") }); Text(option, Modifier.padding(top = 12.dp)) }
                        }
                    } else OutlinedTextField(value, { values[field.key] = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth())
                    else -> OutlinedTextField(value, { values[field.key] = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (field.kind == "password") PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { onSubmit(adminFormPayload(dialog.fields, values)) }
        catch (error: IllegalArgumentException) { validation = error.message }
    }, enabled = !dialog.destructive || confirmation == "DELETE") { Text("Bekräfta") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } })
}

@Composable
private fun AdminValue(key: String, value: JsonElement, card: Boolean = true) {
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(adminLabel(key), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (value) {
                is JsonArray -> { if (value.isEmpty()) Text("Inga poster"); value.forEach { child ->
                    if (child is JsonObject) { child.forEach { (name, item) -> AdminValue(name, item, false) }; HorizontalDivider() }
                    else Text(child.jsonPrimitive.content)
                } }
                is JsonObject -> value.forEach { (name, item) -> AdminValue(name, item, false) }
                else -> Text(if (value is JsonNull) "—" else if (key.endsWith("_at") || key in listOf("last_active", "last_activity", "account_created")) formatAdminTime(value.jsonPrimitive.content) else value.jsonPrimitive.content)
            }
        }
    }
    if (card) AppCard(Modifier.fillMaxWidth()) { content() } else content()
}

private fun adminLabel(key: String): String = (userFields + npcFields + systemFields).firstOrNull { it.key == key }?.label ?: mapOf(
    "users" to "Användare", "active_24h" to "Aktiva senaste dygnet", "active_7d" to "Aktiva senaste veckan", "new_users_7d" to "Nya användare, 7 dagar",
    "activities_today" to "Pass idag (UTC)", "distance_today_km" to "Kilometer idag (UTC)", "activities_7d" to "Pass senaste veckan",
    "enabled_npcs" to "Aktiva NPC", "total_npcs" to "NPC totalt", "npc_activities_today" to "NPC-pass senaste dygnet", "npc_challenges_joined" to "NPC-deltaganden",
    "active_challenges" to "Aktiva utmaningar", "system_challenges" to "Pågående systemutmaningar", "last_simulation" to "Senaste simulering", "errors_24h" to "Simuleringsfel senaste dygnet",
    "participants" to "Deltagare och resultat", "created_at" to "Skapad", "last_sign_in_at" to "Senaste inloggning", "last_activity" to "Senaste aktivitet",
    "total_distance_m" to "Total distans, meter", "distance_30d_m" to "Distans 30 dagar, meter", "activities" to "Aktiviteter (senaste 25)",
    "challenges" to "Utmaningar (senaste 25)", "groups" to "Grupper (högst 25)", "friends" to "Vänner (högst 25)", "dogs" to "Hundar (högst 25)"
)[key] ?: key.replace('_', ' ')

private fun formatAdminTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)
