package se.oxelv.mintracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes

object AvatarPaths {
    const val PROFILE_BUCKET = "profile-avatars"
    const val DOG_BUCKET = "dog-avatars"
    fun profile(userId: String): String = "$userId/profile/avatar-${UUID.randomUUID()}.jpg"
    fun dog(ownerId: String, dogId: String): String = "$ownerId/dogs/$dogId/avatar-${UUID.randomUUID()}.jpg"
    fun isProfile(userId: String, path: String): Boolean = path.startsWith("$userId/profile/")
    fun isDog(ownerId: String, dogId: String, path: String): Boolean = path.startsWith("$ownerId/dogs/$dogId/")
}

/** Only a JPEG created from pixels is uploaded; metadata from the chosen file is not copied. */
object AvatarImageProcessor {
    fun jpeg(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Bilden kunde inte läsas." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Bilden kunde inte öppnas.")
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(270f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(90f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        val scale = minOf(1f, 512f / max(rotated.width, rotated.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true) else rotated
        if (resized !== rotated) rotated.recycle()
        return ByteArrayOutputStream().use { output ->
            check(resized.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "Bilden kunde inte bearbetas." }
            resized.recycle()
            output.toByteArray()
        }
    }
}

class AvatarRepository(private val context: Context, private val db: TrackerDatabase) {
    private data class CachedUrl(val url: String, val validUntil: Long)
    companion object {
        private val urls = ConcurrentHashMap<String, CachedUrl>()
        @Volatile private var cachedViewer: String? = null
    }

    private fun current(userId: String) {
        if (supabase.auth.currentUserOrNull()?.id != userId) throw AccountChangedException()
    }

    private val cleanupPrefs get() = context.getSharedPreferences("avatar_cleanup", Context.MODE_PRIVATE)
    private fun cleanupKey(userId: String) = "pending-$userId"
    private fun recordCleanup(userId: String, bucket: String, path: String) {
        val key = cleanupKey(userId)
        val entries = cleanupPrefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        entries += "$bucket|$path"
        cleanupPrefs.edit().putStringSet(key, entries).apply()
    }
    private fun forgetCleanup(userId: String, bucket: String, path: String) {
        val key = cleanupKey(userId)
        val entries = cleanupPrefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        entries -= "$bucket|$path"
        cleanupPrefs.edit().putStringSet(key, entries).apply()
    }

    suspend fun signedUrl(bucket: String, path: String, viewerId: String): String = withContext(Dispatchers.IO) {
        current(viewerId)
        synchronized(urls) {
            if (cachedViewer != viewerId) { urls.clear(); cachedViewer = viewerId }
        }
        val key = "$viewerId|$bucket|$path"
        val cached = urls[key]
        if (cached != null && cached.validUntil > System.currentTimeMillis()) return@withContext cached.url
        val url = supabase.storage.from(bucket).createSignedUrl(path, 10.minutes)
        current(viewerId)
        urls[key] = CachedUrl(url, System.currentTimeMillis() + 8 * 60_000L)
        url
    }

    suspend fun saveProfile(userId: String, oldPath: String?, uri: Uri): Profile = withContext(Dispatchers.IO) {
        current(userId)
        val bytes = AvatarImageProcessor.jpeg(context, uri)
        val path = AvatarPaths.profile(userId)
        val bucket = supabase.storage.from(AvatarPaths.PROFILE_BUCKET)
        try {
            bucket.upload(path, bytes) { contentType = ContentType.Image.JPEG; upsert = false }
            current(userId)
            bucket.createSignedUrl(path, 10.minutes)
            val updated = ProfileRepository().updateAvatarPath(userId, oldPath, path)
            urls.keys.removeIf { it.contains("|${AvatarPaths.PROFILE_BUCKET}|") && it.endsWith("|$oldPath") }
            cleanup(userId, AvatarPaths.PROFILE_BUCKET, oldPath?.takeIf { AvatarPaths.isProfile(userId, it) })
            updated
        } catch (error: Exception) {
            if (supabase.auth.currentUserOrNull()?.id == userId) runCatching { bucket.delete(path) }
            throw error
        }
    }

    suspend fun removeProfile(userId: String, oldPath: String?): Profile = withContext(Dispatchers.IO) {
        current(userId)
        val updated = ProfileRepository().updateAvatarPath(userId, oldPath, null)
        cleanup(userId, AvatarPaths.PROFILE_BUCKET, oldPath?.takeIf { AvatarPaths.isProfile(userId, it) })
        updated
    }

    suspend fun saveDog(ownerId: String, dogId: String, uri: Uri) = withContext(Dispatchers.IO) {
        current(ownerId)
        val bytes = AvatarImageProcessor.jpeg(context, uri)
        if (db.dogDao().byId(dogId, ownerId)?.syncStatus != SyncStatus.SYNCED) DogRepository(db).sync(ownerId)
        DogCloudLock.mutex.withLock {
        val dog = db.dogDao().byId(dogId, ownerId) ?: error("Hunden finns inte på detta konto.")
        require(dog.archivedAt == null) { "En arkiverad hund kan inte få en ny bild." }
        require(dog.syncStatus == SyncStatus.SYNCED) { "Hunden kunde inte synkas före bilduppladdningen." }
        val path = AvatarPaths.dog(ownerId, dogId)
        val bucket = supabase.storage.from(AvatarPaths.DOG_BUCKET)
        var cloudCommitted = false
        try {
            bucket.upload(path, bytes) { contentType = ContentType.Image.JPEG; upsert = false }
            current(ownerId)
            bucket.createSignedUrl(path, 10.minutes)
            supabase.from("dogs").update({ set("avatar_path", path) }) {
                filter { eq("id", dogId); eq("owner_id", ownerId) }
            }
            cloudCommitted = true
            current(ownerId)
            val remote = supabase.from("dogs").select { filter { eq("id", dogId); eq("owner_id", ownerId) } }.decodeSingle<CloudDog>()
            check(remote.avatarPath == path) { "Hundbilden kunde inte sparas." }
            check(db.dogDao().setAvatarPath(dogId, ownerId, path) == 1) { "Hundbilden sparades i molnet men kunde inte uppdateras lokalt." }
            cleanup(ownerId, AvatarPaths.DOG_BUCKET, dog.avatarPath?.takeIf { AvatarPaths.isDog(ownerId, dogId, it) })
        } catch (error: Exception) {
            if (!cloudCommitted && supabase.auth.currentUserOrNull()?.id == ownerId) runCatching { bucket.delete(path) }
            throw error
        }
        }
    }

    suspend fun removeDog(ownerId: String, dogId: String) = withContext(Dispatchers.IO) {
        DogCloudLock.mutex.withLock {
        current(ownerId)
        val dog = db.dogDao().byId(dogId, ownerId) ?: error("Hunden finns inte på detta konto.")
        supabase.from("dogs").update({ set("avatar_path", null as String?) }) {
            filter { eq("id", dogId); eq("owner_id", ownerId) }
        }
        current(ownerId)
        val remote = supabase.from("dogs").select { filter { eq("id", dogId); eq("owner_id", ownerId) } }.decodeSingle<CloudDog>()
        check(remote.avatarPath == null) { "Hundbilden kunde inte tas bort." }
        check(db.dogDao().setAvatarPath(dogId, ownerId, null) == 1) { "Hundbilden togs bort i molnet men kunde inte uppdateras lokalt." }
        cleanup(ownerId, AvatarPaths.DOG_BUCKET, dog.avatarPath?.takeIf { AvatarPaths.isDog(ownerId, dogId, it) })
        }
    }

    suspend fun retryCleanup(userId: String) = withContext(Dispatchers.IO) {
        current(userId)
        val entries = cleanupPrefs.getStringSet(cleanupKey(userId), emptySet()).orEmpty().toList()
        for (entry in entries) {
            current(userId)
            val bucket = entry.substringBefore('|')
            val path = entry.substringAfter('|', "")
            if (path.isBlank()) continue
            val stillReferenced = runCatching {
                when (bucket) {
                    AvatarPaths.PROFILE_BUCKET -> {
                        if (!AvatarPaths.isProfile(userId, path)) return@runCatching true
                        supabase.from("profiles").select { filter { eq("id", userId) } }
                            .decodeSingle<Profile>().avatarPath == path
                    }
                    AvatarPaths.DOG_BUCKET -> {
                        val dogId = path.split('/').getOrNull(2) ?: return@runCatching true
                        if (!AvatarPaths.isDog(userId, dogId, path)) return@runCatching true
                        supabase.from("dogs").select { filter { eq("id", dogId); eq("owner_id", userId) } }
                            .decodeSingle<CloudDog>().avatarPath == path
                    }
                    else -> true
                }
            }.getOrDefault(true)
            if (!stillReferenced) runCatching {
                supabase.storage.from(bucket).delete(path)
                forgetCleanup(userId, bucket, path)
            }
        }
    }

    private suspend fun cleanup(userId: String, bucket: String, oldPath: String?) {
        if (oldPath == null || supabase.auth.currentUserOrNull()?.id != userId) return
        recordCleanup(userId, bucket, oldPath)
        runCatching {
            supabase.storage.from(bucket).delete(oldPath)
            forgetCleanup(userId, bucket, oldPath)
        }
        urls.remove("$userId|$bucket|$oldPath")
    }
}
