package se.oxelv.mintracker.updates

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** System-owned transfer survives Activity/process recreation. No auth tokens in URLs/headers. */
class ApkDownloadManager(private val context: Context) {
    private val system = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app_update_download",Context.MODE_PRIVATE)
    val folder: File get() = File(requireNotNull(context.getExternalFilesDir(null)),"updates").apply { mkdirs() }
    fun file(policy: AppUpdatePolicy) = File(folder,"${policy.sha256}.apk")
    fun id() = prefs.getLong("download_id",-1)
    fun policy(): AppUpdatePolicy? = runCatching { prefs.getString("policy",null)?.let { kotlinx.serialization.json.Json.decodeFromString<AppUpdatePolicy>(it).validated() } }.getOrNull()
    fun expectedSize() = prefs.getLong("size",0)
    fun start(policy: AppUpdatePolicy, asset: ReleaseAsset): Long {
        cancel()
        folder.listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle("Min Tracker ${policy.versionName}")
            .setDescription("Appuppdatering")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context,"updates","${policy.sha256}.apk")
        val id = system.enqueue(request)
        if (!prefs.edit().putLong("download_id",id).putLong("size",asset.size)
                .putString("policy",kotlinx.serialization.json.Json.encodeToString(AppUpdatePolicy.serializer(),policy)).commit()) {
            system.remove(id)
            error("Nedladdningen kunde inte sparas. Försök igen.")
        }
        return id
    }
    suspend fun await(id: Long, expected: Long, progress: (Int?) -> Unit): File {
        while (true) {
            currentCoroutineContext().ensureActive()
            system.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor.moveToFirst()) { "Nedladdningen har avbrutits. Försök igen." }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                progress(if (expected > 0) ((downloaded * 100 / expected).coerceIn(0,100)).toInt() else null)
                check(downloaded <= expected) { "APK-filens storlek matchar inte releasen." }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val policy = requireNotNull(policy())
                        val file = file(policy)
                        check(file.isFile && file.length() == expected) { "APK-filen är ofullständig. Försök igen." }
                        return file
                    }
                    DownloadManager.STATUS_FAILED -> error("Nedladdningen misslyckades. Kontrollera anslutning och lagringsutrymme och försök igen.")
                }
            }
            delay(500)
        }
    }
    fun cancel() {
        val id = id()
        if (id >= 0) system.remove(id)
        policy()?.let { file(it).delete() }
        prefs.edit().clear().commit()
    }
}
