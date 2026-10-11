package se.oxelv.mintracker.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL

/** Opt-in only: real Android decoder, repository, Room, Storage and signed image download. */
class LiveDogAvatarDeviceTest {
    @Serializable private data class Account(val email: String, val password: String)
    @Test fun chosenImageIsSavedAndDisplayedForOwnedDog() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dog_avatar_test") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "live-social-test-accounts.json")
        val account = try { Json.decodeFromString<List<Account>>(input.readText()).first() } finally { input.delete() }
        require(account.email.endsWith("@mintracker.example.test"))
        AuthRepository().signIn(account.email, account.password)
        val owner = checkNotNull(supabase.auth.currentUserOrNull()?.id)
        val db = TrackerDatabaseProvider.get(context)
        val dogs = DogRepository(db)
        val avatars = AvatarRepository(context, db)
        val dog = dogs.create(owner, "Disposable Android avatar regression", null, null)
        File(context.filesDir, "avatar-regression-id.txt").writeText(dog.id)
        val image = File(context.cacheDir, "synthetic-dog-avatar.png")
        try {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            dogs.sync(owner)
            try { avatars.saveDog(owner, dog.id, Uri.fromFile(image)) }
            catch (error: Exception) { throw AssertionError("Actual dog upload failed: ${error.javaClass.simpleName}; credentials and response omitted") }
            val path = checkNotNull(db.dogDao().byId(dog.id, owner)?.avatarPath)
            assertTrue(AvatarPaths.isDog(owner, dog.id, path))
            val signed = avatars.signedUrl(AvatarPaths.DOG_BUCKET, path, owner)
            val bytes = URL(signed).openConnection().apply { connectTimeout = 15000; readTimeout = 15000 }.getInputStream().use { it.readBytes() }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("A displayed signed image must decode as pixels", decoded)
            decoded?.recycle()
            avatars.removeDog(owner, dog.id)
            assertNull(db.dogDao().byId(dog.id, owner)?.avatarPath)
        } finally {
            image.delete()
            runCatching { avatars.removeDog(owner, dog.id) }
            dogs.archive(owner, dog.id); dogs.sync(owner)
            AuthRepository().signOut()
        }
    }
}
