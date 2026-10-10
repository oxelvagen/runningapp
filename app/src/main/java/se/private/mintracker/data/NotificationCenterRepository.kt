package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant

data class NotificationSnapshot(val unread: Long = 0, val verified: Boolean = false,
    val cached: NotificationPage? = null, val revision: Long = 0)

/** One instance per account composition. Cache is memory-only and disappears on logout/process exit. */
class NotificationCenterRepository(val userId: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(NotificationSnapshot())
    val state = mutable.asStateFlow()
    private val requests = Mutex()
    fun clearCache() { mutable.value = NotificationSnapshot() }
    private fun ensureActor() { if (supabase.auth.currentUserOrNull()?.id != userId) throw AccountChangedException() }
    private suspend inline fun <reified T> rpc(name: String, params: JsonObject = buildJsonObject {}): T {
        ensureActor()
        val result = supabase.postgrest.rpc(name, params)
        ensureActor()
        return json.decodeFromString<T>(result.data)
    }
    suspend fun refresh() = requests.withLock {
        try {
            val count: Long = rpc("get_notification_unread_count")
            mutable.update { it.copy(unread = count, verified = true, revision = it.revision + 1) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { mutable.update { it.copy(verified = false) } }
    }
    suspend fun page(unread: Boolean = false, cursor: NotificationCursor? = null): NotificationPage = requests.withLock {
        val page: NotificationPage = rpc("get_notification_center", buildJsonObject {
            put("p_unread_only", unread); put("p_limit", 20)
            cursor?.let { put("p_before_at", it.createdAt); put("p_before_id", it.id) }
        })
        check(page.items.all { it.recipientId == userId })
        mutable.update { it.copy(unread = page.unreadCount, verified = true,
            cached = if (!unread && cursor == null) page else it.cached) }
        page
    }
    suspend fun open(id: String): NotificationTarget = requests.withLock {
        val target: NotificationTarget = rpc("read_notification", buildJsonObject { put("p_id", id) })
        mutable.update { snapshot -> snapshot.copy(unread = target.unreadCount, verified = true,
            cached = snapshot.cached?.copy(unreadCount = target.unreadCount, items = snapshot.cached.items.mapNotNull {
                if (it.id != id) it else if (target.available) it.copy(readAt = Instant.now().toString()) else null
            })) }
        target
    }
    suspend fun readAll(): Long = requests.withLock {
        val count: Long = rpc("read_all_notifications")
        mutable.update { snapshot -> snapshot.copy(unread = count, verified = true,
            cached = snapshot.cached?.copy(unreadCount = count, items = snapshot.cached.items.map { it.copy(readAt = Instant.now().toString()) })) }
        count
    }
    suspend fun preferences(): NotificationPreferences = rpc("get_notification_preferences")
    suspend fun setPreferences(value: NotificationPreferences): NotificationPreferences {
        val result: NotificationPreferences = rpc("set_notification_preferences", buildJsonObject {
            put("p_categories", json.encodeToJsonElement(value.categories)); put("p_expected_revision", value.revision)
        })
        mutable.update { it.copy(cached = null) }
        refresh()
        return result
    }
    suspend fun activity(id: String): SharedNotificationActivity = rpc("get_notification_activity", buildJsonObject { put("p_activity_id", id) })
}
