package se.oxelv.mintracker.updates

import android.content.Context
import se.oxelv.mintracker.data.SUPABASE_PROJECT_URL
import se.oxelv.mintracker.data.SUPABASE_PUBLISHABLE_KEY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class AppUpdateRepository(context: Context) {
    private val prefs = context.getSharedPreferences("app_update_policy", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun cached(): AppUpdatePolicy? = runCatching { prefs.getString("policy",null)?.let { json.decodeFromString<AppUpdatePolicy>(it).validated() } }.getOrNull()
    fun checkedAt() = prefs.getLong("checked_at",0)
    fun snoozed(revision: Long) = prefs.getLong("dismissed_revision",-1) == revision &&
        System.currentTimeMillis() < prefs.getLong("dismissed_until",0)
    fun snooze(revision: Long) { prefs.edit().putLong("dismissed_revision",revision)
        .putLong("dismissed_until",System.currentTimeMillis()+24*60*60_000L).apply() }
    fun store(policy: AppUpdatePolicy, now: Long) {
        check(prefs.edit().putString("policy", json.encodeToString(AppUpdatePolicy.serializer(),policy)).putLong("checked_at",now).commit()) { "Versionspolicyn kunde inte sparas lokalt." }
    }
    suspend fun fetch(): AppUpdatePolicy? = withContext(Dispatchers.IO) {
        // Dedicated anonymous request: works before login and during account/maintenance restrictions.
        val body = https("$SUPABASE_PROJECT_URL/rest/v1/app_update_config?id=eq.true&select=*", mapOf("apikey" to SUPABASE_PUBLISHABLE_KEY))
        val rows = Json.parseToJsonElement(body).jsonArray
        require(rows.size <= 1)
        rows.singleOrNull()?.let { json.decodeFromString<AppUpdatePolicy>(it.toString()).validated() }
    }
    suspend fun release(repo: String, policy: AppUpdatePolicy): ReleaseAsset = withContext(Dispatchers.IO) {
        val repository = GitHubReleaseRules.repository(repo)
        val tag = URLEncoder.encode(policy.tag, "UTF-8").replace("+","%20")
        val body = https("https://api.github.com/repos/$repository/releases/tags/$tag", mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"))
        GitHubReleaseRules.asset(body,repository,policy)
    }
    private fun https(url: String, headers: Map<String,String>): String {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "MinTracker-Android")
            headers.forEach { (k,v) -> connection.setRequestProperty(k,v) }
            when (connection.responseCode) {
                200 -> Unit
                404 -> error("Versionskonfigurationen eller GitHub-releasen saknas.")
                403,429 -> error("Tjänsten begränsar anropen. Försök igen om en stund.")
                else -> error("Uppdateringstjänsten kunde inte nås. Försök igen.")
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 524_288) { "Versionssvaret är för stort." }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}
