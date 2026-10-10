package se.oxelv.mintracker.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarPathsTest {
    private val user = "550e8400-e29b-41d4-a716-446655440000"
    private val other = "550e8400-e29b-41d4-a716-446655440001"
    private val dog = "550e8400-e29b-41d4-a716-446655440002"

    @Test fun profilePathUsesOwnAccountAndUniqueJpegName() {
        val first = AvatarPaths.profile(user)
        val second = AvatarPaths.profile(user)
        assertTrue(first.matches(Regex("$user/profile/avatar-[0-9a-f-]{36}\\.jpg")))
        assertFalse(first == second)
        assertTrue(AvatarPaths.isProfile(user, first))
        assertFalse(AvatarPaths.isProfile(other, first))
    }

    @Test fun dogPathRequiresCorrectOwnerAndDog() {
        val path = AvatarPaths.dog(user, dog)
        assertTrue(AvatarPaths.isDog(user, dog, path))
        assertFalse(AvatarPaths.isDog(other, dog, path))
        assertFalse(AvatarPaths.isDog(user, other, path))
    }

    @Test fun oldProfileAndDogWithoutImagesRemainReadable() {
        val profile = Json.decodeFromString<Profile>("""{"id":"$user","display_name":"Andreas"}""")
        assertEquals(null, profile.avatarPath)
        assertEquals(null, profile.avatarUrl)
        val cloudDog = Json.decodeFromString<CloudDog>("""{"id":"$dog","owner_id":"$user","name":"Luna","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}""")
        assertEquals(null, cloudDog.avatarPath)
    }

    @Test fun cloudModelsKeepPermanentObjectPaths() {
        val profilePath = AvatarPaths.profile(user)
        val dogPath = AvatarPaths.dog(user, dog)
        val profile = Json.decodeFromString<Profile>("""{"id":"$user","display_name":"Andreas","avatar_path":"$profilePath"}""")
        val cloudDog = Json.decodeFromString<CloudDog>("""{"id":"$dog","owner_id":"$user","name":"Luna","avatar_path":"$dogPath","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}""")
        assertEquals(profilePath, profile.avatarPath)
        assertEquals(dogPath, cloudDog.avatarPath)
    }
}
