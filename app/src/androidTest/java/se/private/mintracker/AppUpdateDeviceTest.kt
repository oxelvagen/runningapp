package se.oxelv.mintracker

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.updates.*
import java.io.File
import com.android.apksig.ApkVerifier

class AppUpdateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun policyPersistsAcrossRepositoryInstancesForOfflineStart() {
        val preferences=context.getSharedPreferences("app_update_policy",0)
        val backup=preferences.getString("policy",null); val timestamp=preferences.getLong("checked_at",0)
        try {
            val p=AppUpdatePolicy(999,999,"test","vtest","MinTracker-test.apk","a".repeat(64),revision=1,updatedAt="2026-10-10T08:00:00Z")
            AppUpdateRepository(context).store(p,123)
            assertEquals(p,AppUpdateRepository(context).cached())
            assertTrue(UpdateRules.block(BuildConfig.VERSION_CODE.toLong(),AppUpdateRepository(context).cached(),false))
        } finally { preferences.edit().putString("policy",backup).putLong("checked_at",timestamp).commit() }
    }
    @Test fun missingDownloadIsReportedAsCancelled() = runBlocking {
        try { ApkDownloadManager(context).await(Long.MAX_VALUE,123) {}; fail("Missing download accepted") }
        catch (e: IllegalStateException) {assertTrue(e.message!!.contains("avbrutits"))}
    }
    @Test fun apkCryptographicVerifierRejectsTamperedArchive() {
        val original=File(context.applicationInfo.sourceDir)
        assertTrue(ApkVerifier.Builder(original).setMinCheckedPlatformVersion(android.os.Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(android.os.Build.VERSION.SDK_INT).build().verify().isVerified)
        val tampered=File(context.cacheDir,"update-test-tampered.apk")
        try {
            original.copyTo(tampered,overwrite=true)
            java.io.RandomAccessFile(tampered,"rw").use {it.seek(100); val byte=it.readByte().toInt(); it.seek(100); it.writeByte(byte xor 1)}
            val accepted=runCatching { ApkVerifier.Builder(tampered).build().verify().isVerified }.getOrDefault(false)
            assertFalse(accepted)
        } finally {tampered.delete()}
    }
    @Test fun installHandoffUsesOnlyPrivateContentUri() {
        val directory=File(context.filesDir,"updates").apply {mkdirs()}
        val apk=File(directory,"uri-test.apk").apply {writeText("test")}
        try {
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",apk)
            assertEquals("content",uri.scheme)
            assertEquals("${context.packageName}.fileprovider",uri.authority)
        } finally {apk.delete()}
    }
}
