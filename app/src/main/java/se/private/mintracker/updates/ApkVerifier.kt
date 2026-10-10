package se.oxelv.mintracker.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

class UpdateApkVerifier(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File, policy: AppUpdatePolicy) {
        val digest = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer,0,count) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        require(digest == policy.sha256) { "APK-filens SHA-256 stämmer inte. Filen har avvisats." }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName,flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath,flags) ?: error("Filen är inte en giltig APK.")
        // Cryptographically verify the complete APK for the actual platform, not merely its manifest/certificates.
        val verified = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        require(verified.isVerified) { "APK-filens digitala signatur är ogiltig." }
        val trusted = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo!!.apkContentsSigners.map { it.toByteArray() }
            else requireNotNull(installed.signatures).map { it.toByteArray() }
        val candidate = verified.signerCertificates
        fun same(a: List<ByteArray>, b: List<ByteArray>) = a.size == b.size && a.all { x -> b.any { x.contentEquals(it) } }
        var compatible = same(trusted,candidate.map { it.encoded })
        // Forward rotation only, single signer, proven lineage with installed-data capability.
        if (!compatible && Build.VERSION.SDK_INT >= 28 && trusted.size == 1 && candidate.size == 1) {
            val lineage = verified.signingCertificateLineage
            if (lineage != null) {
                val history = lineage.certificatesInLineage
                val ancestor = history.firstOrNull { it.encoded.contentEquals(trusted.single()) }
                compatible = ancestor != null && history.last().encoded.contentEquals(candidate.single().encoded) &&
                    lineage.getSignerCapabilities(ancestor).hasInstalledData()
            }
        }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        UpdateRules.verifyIdentity(policy,installedCode,context.packageName,archive.packageName,code,archive.versionName,digest,compatible)
    }
}
