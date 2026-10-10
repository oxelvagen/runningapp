package se.oxelv.mintracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val username: String? = null,
    val bio: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
internal data class NewProfile(
    val id: String,
    @SerialName("display_name") val displayName: String
)
