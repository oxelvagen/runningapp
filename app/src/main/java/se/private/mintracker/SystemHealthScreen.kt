package se.oxelv.mintracker

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import se.oxelv.mintracker.data.*

private val healthStatuses = linkedMapOf("NEW" to "Nytt", "INVESTIGATING" to "Utreds", "RESOLVED" to "Löst", "IGNORED" to "Ignorerat")
@Composable
internal fun SystemHealthScreen(repository: AdminRepository, revision: Int, modifier: Modifier = Modifier,
    request: suspend (String, JsonObject) -> JsonElement = { name, parameters -> repository.rpc(name, parameters) }) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("ALL") }
    var severity by remember { mutableStateOf("ALL") }
    var status by remember { mutableStateOf("ALL") }
    var page by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<JsonObject?>(null) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun work(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Systemhälsa kunde inte hämtas eller ändras. Kontrollera anslutning och adminbehörighet." }
            finally { busy = false }
        }
    }
    LaunchedEffect(query, category, severity, status, page, revision, reload) {
        overview = null; error = null
        try {
            delay(300)
            overview = request("admin_system_health", buildJsonObject {
                put("p_page", page); put("p_query", query); put("p_category", category)
                put("p_severity", severity); put("p_status", status)
            }).jsonObject
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Systemhälsa kunde inte hämtas. Försök igen." }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Systemhälsa", style = MaterialTheme.typography.titleLarge)
            Text("Teknisk klientdiagnostik. Uppgifterna är obetrodda och visar registrerade händelser, inte säkra orsaker. GPS-rutter och privata meddelanden ingår inte.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy || (overview == null && error == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val selected = detail
        if (selected != null) {
            val group = selected["group"] as? JsonObject
            item {
                TextButton(onClick = { detail = null }) { Text("‹ Alla grupper") }
                Text(group?.text("code").orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("${group?.text("category")} · ${group?.text("severity")} · ${group?.text("occurrences")} förekomster")
                Text("Först: ${group?.text("first_seen")}\nSenast: ${group?.text("last_seen")}")
                Text("Status: ${healthStatuses[group?.text("status")].orEmpty()}")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    healthStatuses.forEach { (code, label) ->
                        OutlinedButton(enabled = !busy, onClick = { work {
                            request("admin_system_health_status", buildJsonObject { put("p_fingerprint", group?.text("fingerprint")); put("p_status", code) })
                            detail = request("admin_system_health_detail", buildJsonObject { put("p_fingerprint", group?.text("fingerprint")) }).jsonObject
                            reload++
                        } }) { Text(label) }
                    }
                }
                Text("Berörda versioner: ${selected["versions"]}")
            }
            items((selected["reports"] as? JsonArray).orEmpty()) { element ->
                val report = element.jsonObject
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${report.text("occurred_at")} · ${report.text("version_name")} (${report.text("version_code")})")
                        Text("Android ${report.text("android_api")} · ${report.text("device_model")}")
                        Text("Förgrund: ${report.text("foreground")} · Träning: ${report.text("training")}")
                        Text("Rapport: ${report.text("id")}\nKonto: ${report.text("user_id")}\nInstallation: ${report.text("installation_id")}")
                        Text("Sanerad stack:\n" + (report["frames"] as? JsonArray).orEmpty().joinToString("\n") { it.jsonPrimitive.content }.ifBlank { "Ingen tillgänglig" })
                    }
                }
            }
        } else {
            item {
                overview?.let { data ->
                    Text("Senaste 24 timmarna", style = MaterialTheme.typography.titleMedium)
                    Text("Fel: ${data.text("errors_24h")} · Krascher: ${data.text("crashes_24h")}\nBerörda konton: ${data.text("affected_users_24h")} · Kritiska: ${data.text("critical_24h")}")
                    Text("INFO-händelser räknas inte som fel. Avbrutna processer räknas inte automatiskt som krascher.")
                    listOf("by_version" to "Appversion", "by_android" to "Android", "by_category" to "Kategori").forEach { (key, label) ->
                        Text("$label: " + (data[key] as? JsonArray).orEmpty().joinToString("; ") { entry -> entry.jsonObject.entries.joinToString(" · ") { "${it.key}: ${it.value.jsonPrimitive.content}" } }.ifBlank { "Inga fel" })
                    }
                }
                OutlinedTextField(query, { query = it.take(64); page = 0 }, label = { Text("Sök felkod") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HealthFilter("Kategori", category, listOf("ALL", "CRASH", "AUTH", "GPS", "ACTIVITY", "SYNC", "STORAGE", "NETWORK", "DATABASE", "SOCIAL", "CHALLENGE", "UI", "UPDATE", "UNKNOWN")) { category = it; page = 0 }
                HealthFilter("Nivå", severity, listOf("ALL", "INFO", "WARNING", "ERROR", "CRITICAL")) { severity = it; page = 0 }
                HealthFilter("Status", status, listOf("ALL") + healthStatuses.keys) { status = it; page = 0 }
            }
            val groups = (overview?.get("groups") as? JsonArray).orEmpty()
            if (overview != null && groups.isEmpty()) item { Text("Inga rapportgrupper matchar filtret.") }
            items(groups, key = { it.jsonObject.text("fingerprint") }) { element ->
                val group = element.jsonObject
                ElevatedCard(onClick = { work { detail = request("admin_system_health_detail", buildJsonObject { put("p_fingerprint", group.text("fingerprint")) }).jsonObject } }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(group.text("code"), style = MaterialTheme.typography.titleMedium)
                        Text("${group.text("severity")} · ${group.text("category")} · ${healthStatuses[group.text("status")]} · ${group.text("occurrences")} förekomster")
                        Text("${group.text("version_name")} · ${group.text("device_model")}\nSenast ${group.text("last_seen")}")
                    }
                }
            }
            item { Row {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Föregående") }
                Text("Sida ${page + 1}")
                TextButton(onClick = { page++ }, enabled = groups.size == 50 && page < 200) { Text("Nästa") }
            } }
        }
    }
}

@Composable
private fun HealthFilter(label: String, current: String, values: List<String>, onChange: (String) -> Unit) {
    Text(label)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { code -> FilterChip(current == code, { onChange(code) }, label = { Text(if (code == "ALL") "Alla" else healthStatuses[code] ?: code) }) }
    }
}
