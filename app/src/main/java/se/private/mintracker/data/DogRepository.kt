package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

@Serializable
data class CloudDog(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val breed: String? = null,
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("archived_at") val archivedAt: String? = null
)

class DogRepository(private val db: TrackerDatabase) {
    fun dogs(ownerId: String): Flow<List<DogEntity>> = db.dogDao().dogs(ownerId)

    suspend fun create(ownerId: String, name: String, breed: String?, birthDate: String?): DogEntity = withContext(Dispatchers.IO) {
        requireCurrentUser(ownerId)
        val cleanName = validName(name)
        val cleanBreed = validBreed(breed)
        val cleanBirthDate = validBirthDate(birthDate)
        val now = System.currentTimeMillis()
        val dog = DogEntity(UUID.randomUUID().toString(), ownerId, cleanName, cleanBreed, cleanBirthDate, createdAt = now, updatedAt = now)
        db.dogDao().insertIfAbsent(dog)
        dog
    }

    suspend fun edit(ownerId: String, id: String, name: String, breed: String?, birthDate: String?) = withContext(Dispatchers.IO) {
        requireCurrentUser(ownerId)
        require(db.dogDao().edit(id, ownerId, validName(name), validBreed(breed), validBirthDate(birthDate), System.currentTimeMillis()) == 1) {
            "Hunden finns inte eller är arkiverad."
        }
    }

    suspend fun archive(ownerId: String, id: String) = withContext(Dispatchers.IO) {
        requireCurrentUser(ownerId)
        require(db.dogDao().archive(id, ownerId, System.currentTimeMillis()) == 1) { "Hunden är redan arkiverad eller finns inte." }
    }

    suspend fun sync(ownerId: String) = withContext(Dispatchers.IO) {
        DogCloudLock.mutex.withLock {
        requireCurrentUser(ownerId)
        for (dog in db.dogDao().pending(ownerId)) {
            requireCurrentUser(ownerId)
            try {
                val remote = supabase.from("dogs").select { filter { eq("id", dog.id); eq("owner_id", ownerId) } }.decodeSingleOrNull<CloudDog>()
                requireCurrentUser(ownerId)
                if (remote?.archivedAt != null && dog.archivedAt == null) {
                    val archived = remote.toLocal()
                    check(db.dogDao().acceptRemoteArchive(dog.id, ownerId, dog.updatedAt, archived.name, archived.breed, archived.birthDate, archived.avatarPath, archived.archivedAt!!, archived.updatedAt) == 1) {
                        "Hunden ändrades under synkningen. Försöker igen."
                    }
                    continue
                }
                supabase.from("dogs").upsert(dog.toCloud())
                requireCurrentUser(ownerId)
                db.dogDao().setStatus(dog.id, ownerId, dog.updatedAt, SyncStatus.SYNCED)
            } catch (error: AccountChangedException) {
                throw error
            } catch (error: Exception) {
                db.dogDao().setStatus(dog.id, ownerId, dog.updatedAt, SyncStatus.ERROR)
                throw error
            }
        }

        var offset = 0L
        while (true) {
            requireCurrentUser(ownerId)
            val page = supabase.from("dogs").select {
                filter { eq("owner_id", ownerId) }
                order("id", Order.ASCENDING)
                range(offset..offset + 199)
            }.decodeList<CloudDog>()
            for (remote in page) {
                requireCurrentUser(ownerId)
                check(remote.ownerId == ownerId) { "En hund tillhör ett annat konto." }
                val incoming = remote.toLocal()
                val existing = db.dogDao().byId(incoming.id, ownerId)
                if (existing == null) db.dogDao().insertIfAbsent(incoming)
                else if (existing.syncStatus == SyncStatus.SYNCED && incoming.updatedAt >= existing.updatedAt) {
                    db.dogDao().updateSynced(incoming.id, ownerId, incoming.name, incoming.breed, incoming.birthDate, incoming.avatarPath, incoming.archivedAt, incoming.updatedAt)
                }
            }
            if (page.size < 200) break
            offset += page.size
        }
        }
    }

    private fun requireCurrentUser(ownerId: String) {
        if (supabase.auth.currentUserOrNull()?.id != ownerId) throw AccountChangedException()
    }
}

internal object DogCloudLock { val mutex = Mutex() }

private fun validName(value: String): String = value.trim().also { require(it.length in 1..80) { "Ange ett hundnamn på högst 80 tecken." } }
private fun validBreed(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 120) { "Rasen får vara högst 120 tecken." } }
private fun validBirthDate(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also { LocalDate.parse(it) }

private fun DogEntity.toCloud() = CloudDog(id, ownerId, name, breed, birthDate, avatarPath, toTimestamptz(createdAt), toTimestamptz(updatedAt), archivedAt?.let(::toTimestamptz))
private fun CloudDog.toLocal() = DogEntity(id, ownerId, name, breed, birthDate, avatarPath, fromTimestamptz(createdAt), fromTimestamptz(updatedAt), archivedAt?.let(::fromTimestamptz), SyncStatus.SYNCED)
