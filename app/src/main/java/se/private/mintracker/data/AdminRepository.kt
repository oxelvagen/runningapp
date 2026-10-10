package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*

/** No privileged credential is present here. Every RPC/Edge request authorizes on the server. */
class AdminRepository {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun isAdmin(): Boolean = rpc("is_admin").jsonPrimitive.boolean
    suspend fun dashboard(): JsonObject = rpc("admin_dashboard").jsonObject
    suspend fun configuration(): JsonObject = rpc("client_configuration").jsonObject
    suspend fun page(section: String, page: Int, query: String, filter: String): List<JsonObject> = rpc("admin_page", buildJsonObject {
        put("p_section", section); put("p_page", page); put("p_query", query); put("p_filter", filter)
    }).jsonArray.map { it.jsonObject }
    suspend fun detail(section: String, id: String): JsonObject = rpc("admin_detail", buildJsonObject {
        put("p_section", section); put("p_id", id)
    }).jsonObject
    suspend fun command(action: String, id: String? = null, data: JsonObject = buildJsonObject {}): JsonElement = rpc("admin_command", buildJsonObject {
        put("p_action", action); put("p_id", id); put("p_data", data)
    })
    suspend fun preview(id: String): List<JsonObject> = rpc("admin_preview_npc", buildJsonObject { put("p_id", id) }).jsonArray.map { it.jsonObject }
    suspend fun simulate(id: String? = null): JsonObject = rpc("run_npc_simulation", buildJsonObject { put("p_npc_id", id) }).jsonObject
    suspend fun account(action: String, id: String?, data: JsonObject = buildJsonObject {}): JsonElement {
        val actor = supabase.auth.currentUserOrNull()?.id ?: error("Logga in igen.")
        val token = supabase.auth.currentAccessTokenOrNull() ?: error("Logga in igen.")
        val response = http.post("$SUPABASE_PROJECT_URL/functions/v1/admin-account") {
            bearerAuth(token)
            header("apikey", SUPABASE_PUBLISHABLE_KEY)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("action", action); put("id", id)
                put("confirmation", if (action.contains("DELETE")) "DELETE" else "CONFIRM")
                data.forEach { (key, value) -> put(key, value) }
            }.toString())
        }
        check(supabase.auth.currentUserOrNull()?.id == actor) { "Kontot har ändrats." }
        check(response.status.isSuccess()) { "Åtgärden kunde inte slutföras (${response.status.value})." }
        return json.parseToJsonElement(response.bodyAsText())
    }
    private suspend fun rpc(name: String, params: JsonObject = buildJsonObject {}): JsonElement {
        val actor = supabase.auth.currentUserOrNull()?.id
        val result = supabase.postgrest.rpc(name, params).data
        check(supabase.auth.currentUserOrNull()?.id == actor) { "Kontot har ändrats." }
        return json.parseToJsonElement(result)
    }
    companion object { private val http = HttpClient(Android) }
}

fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
