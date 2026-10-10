package se.oxelv.mintracker.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable
data class AppUpdatePolicy(
    @SerialName("latest_version_code") val latestCode: Long,
    @SerialName("minimum_version_code") val minimumCode: Long,
    @SerialName("latest_version_name") val versionName: String,
    @SerialName("github_release_tag") val tag: String,
    @SerialName("apk_asset_name") val assetName: String,
    @SerialName("apk_sha256") val sha256: String,
    @SerialName("release_notes") val notes: String = "",
    @SerialName("policy_revision") val revision: Long,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun validated(): AppUpdatePolicy = apply {
        require(minimumCode > 0 && latestCode >= minimumCode && latestCode <= Int.MAX_VALUE)
        require(versionName.isNotBlank() && versionName.length <= 100)
        require(tag.isNotBlank() && tag.length <= 200 && notes.length <= 20000)
        require(assetName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*[.]apk")) && assetName.length <= 200)
        require(sha256.matches(Regex("[0-9a-f]{64}")) && revision > 0)
        java.time.OffsetDateTime.parse(updatedAt)
    }
}

enum class UpdateRequirement { CURRENT, OPTIONAL, REQUIRED, UNKNOWN }
object UpdateRules {
    fun requirement(installed: Long, policy: AppUpdatePolicy?) = when {
        policy == null -> UpdateRequirement.UNKNOWN
        installed < policy.minimumCode -> UpdateRequirement.REQUIRED
        installed < policy.latestCode -> UpdateRequirement.OPTIONAL
        else -> UpdateRequirement.CURRENT
    }
    fun accept(old: AppUpdatePolicy?, incoming: AppUpdatePolicy): Boolean {
        incoming.validated()
        return old == null || (incoming.revision > old.revision &&
            java.time.OffsetDateTime.parse(incoming.updatedAt) >= java.time.OffsetDateTime.parse(old.updatedAt)) || incoming == old
    }
    fun block(installed: Long, policy: AppUpdatePolicy?, unsavedTraining: Boolean) =
        requirement(installed, policy) == UpdateRequirement.REQUIRED && !unsavedTraining

    fun verifyIdentity(policy: AppUpdatePolicy, installed: Long, expectedPackage: String,
                       actualPackage: String, code: Long, name: String?, digest: String, signingCompatible: Boolean) {
        require(digest == policy.sha256) { "APK-filens SHA-256 stämmer inte. Filen har avvisats." }
        require(actualPackage == expectedPackage) { "APK-filen tillhör inte Min Tracker." }
        require(code > installed && code == policy.latestCode && name == policy.versionName) { "APK-filen har fel version. Filen har avvisats." }
        require(signingCompatible) { "APK-filens signering matchar inte den installerade appen." }
    }
}

data class ReleaseAsset(val url: String, val size: Long)
object GitHubReleaseRules {
    fun repository(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "GitHub-repository saknas i appens konfiguration." }
        return value
    }
    fun asset(json: String, repo: String, policy: AppUpdatePolicy): ReleaseAsset {
        repository(repo)
        val root = Json.parseToJsonElement(json).jsonObject
        require(root["tag_name"]?.jsonPrimitive?.content == policy.tag && root["draft"]?.jsonPrimitive?.booleanOrNull == false) { "GitHub-releasen matchar inte versionskonfigurationen." }
        val assets = root["assets"]?.jsonArray.orEmpty().filter { it.jsonObject["name"]?.jsonPrimitive?.content == policy.assetName }
        require(assets.size == 1) { "APK-filen saknas i GitHub-releasen." }
        val a = assets.single().jsonObject
        val url = a.getValue("browser_download_url").jsonPrimitive.content
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            uri.path.startsWith("/$repo/releases/download/") && uri.fragment == null) { "APK-adressen är inte en betrodd GitHub Release-adress." }
        val size = a.getValue("size").jsonPrimitive.long
        require(size in 1..500_000_000 && a["state"]?.jsonPrimitive?.content == "uploaded") { "APK-filen är ofullständig eller för stor." }
        return ReleaseAsset(url,size)
    }
}

enum class UpdatePhase { IDLE, RESOLVING, DOWNLOADING, VERIFYING, READY, INSTALLER, ERROR }
data class AppUpdateState(
    val policy: AppUpdatePolicy? = null, val checking: Boolean = false,
    val checkedAt: Long = 0, val checkError: String? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE, val percent: Int? = null,
    val error: String? = null, val prompt: Boolean = false,
)
