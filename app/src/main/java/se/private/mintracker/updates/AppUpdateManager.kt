package se.oxelv.mintracker.updates

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.oxelv.mintracker.BuildConfig
import se.oxelv.mintracker.data.Diagnostics
import se.oxelv.mintracker.data.DiagnosticCode
import se.oxelv.mintracker.tracking.TrackingState
import java.io.File

/** App lifetime, independent of authentication or Compose navigation. */
class AppUpdateManager private constructor(private val context: Context) {
    private val repository = AppUpdateRepository(context)
    private val downloads = ApkDownloadManager(context)
    private val verifier = UpdateApkVerifier(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checkMutex = Mutex()
    private var lastAttempt = -1L
    private var downloadJob: Job? = null
    private var ready: File? = null
    private var readyPolicy: AppUpdatePolicy? = null
    private val initialPolicy = repository.cached()
    private val mutable = MutableStateFlow(AppUpdateState(initialPolicy,checkedAt=repository.checkedAt(),
        prompt=initialPolicy != null && UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(),initialPolicy)==UpdateRequirement.OPTIONAL &&
            System.currentTimeMillis()-repository.checkedAt() in 0..7*24*60*60_000L && !repository.snoozed(initialPolicy.revision)))
    val state = mutable.asStateFlow()
    init { resumeDownload(); check() }

    fun check(manual: Boolean = false) { scope.launch {
        checkMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (!manual && lastAttempt >= 0 && now-lastAttempt < 15*60_000) return@withLock
            lastAttempt = now
            mutable.update { it.copy(checking=true,checkError=null) }
            try {
                val incoming = repository.fetch()
                if (incoming == null) {
                    mutable.update { it.copy(checkError="Ingen uppdateringsversion har publicerats ännu.") }
                } else {
                    val previous = state.value.policy
                    check(UpdateRules.accept(previous,incoming)) { "Ett äldre eller motstridigt versionssvar avvisades." }
                    val checkedAt = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { repository.store(incoming,checkedAt) }
                    if (previous != null && previous != incoming && downloads.policy()?.let { it != incoming } == true) cancelDownload()
                    mutable.update { it.copy(policy=incoming,checkedAt=checkedAt,checkError=null,
                        prompt=UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(),incoming)==UpdateRequirement.OPTIONAL &&
                            (manual || !repository.snoozed(incoming.revision))) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(checkError=message(e,"Kunde inte kontrollera uppdateringar. Kontrollera internet och försök igen.")) } }
            finally { mutable.update { it.copy(checking=false) } }
        }
    } }
    fun dismiss() { state.value.policy?.let { repository.snooze(it.revision) }; mutable.update { it.copy(prompt=false) } }
    fun show() { mutable.update { it.copy(prompt=true) } }
    fun download() {
        if (downloadJob?.isActive == true) return
        val policy = state.value.policy ?: return
        if (UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(),policy)==UpdateRequirement.CURRENT) return
        mutable.update { it.copy(phase=UpdatePhase.RESOLVING,error=null,prompt=true) }
        downloadJob = scope.launch {
            try {
                val asset = repository.release(BuildConfig.UPDATE_REPOSITORY,policy)
                check(state.value.policy == policy) { "Versionspolicyn ändrades. Försök igen." }
                val id = withContext(Dispatchers.IO) { downloads.start(policy,asset) }
                transfer(id,policy,asset.size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
        }
    }
    private fun resumeDownload() {
        val pending = downloads.policy() ?: return
        if (pending != state.value.policy || pending.latestCode <= BuildConfig.VERSION_CODE) { downloads.cancel(); return }
        mutable.update { it.copy(prompt=true) }
        downloadJob = scope.launch { try { transfer(downloads.id(),pending,downloads.expectedSize()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) } }
    }
    private suspend fun transfer(id: Long, policy: AppUpdatePolicy, size: Long) {
        mutable.update { it.copy(phase=UpdatePhase.DOWNLOADING,error=null) }
        val downloaded = withContext(Dispatchers.IO) { downloads.await(id,size) { percent -> mutable.update { it.copy(percent=percent) } } }
        mutable.update { it.copy(phase=UpdatePhase.VERIFYING) }
        val file = withContext(Dispatchers.IO) {
            // The verified/installed copy is internal-only, including on Android 8/9.
            val directory = File(context.filesDir,"updates").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val protectedCopy = File(directory,"${policy.sha256}.apk")
            downloaded.copyTo(protectedCopy,overwrite=true)
            verifier.verify(protectedCopy,policy)
            protectedCopy
        }
        check(state.value.policy == policy) { "Versionspolicyn ändrades. Försök igen." }
        ready = file; readyPolicy = policy
        mutable.update { it.copy(phase=UpdatePhase.READY,percent=100) }
    }
    /** Never called automatically. Reverify immediately before handing a content URI to Android. */
    suspend fun installIntent(unsavedTraining: Boolean): Intent {
        check(!unsavedTraining && !TrackingState.session.value.running) { "Avsluta och spara träningspasset före installationen." }
        val policy = requireNotNull(readyPolicy) { "Ladda ner uppdateringen först." }
        check(policy == state.value.policy) { "Versionspolicyn ändrades. Ladda ner uppdateringen igen." }
        val file = requireNotNull(ready)
        withContext(Dispatchers.IO) { verifier.verify(file,policy) }
        check(!TrackingState.session.value.running && policy == state.value.policy)
        val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
    }
    fun installerOpened() { mutable.update { it.copy(phase=UpdatePhase.INSTALLER,error=null) } }
    fun installerReturned() { mutable.update { it.copy(phase=UpdatePhase.READY,error="Installationen slutfördes inte. Du kan försöka igen.") }; check(true) }
    fun installError(e: Exception) { Diagnostics.report(DiagnosticCode.UPDATE_FAILED, e); mutable.update { it.copy(error=message(e,"Installationsdialogen kunde inte öppnas. Försök igen.")) } }
    fun cancelDownload() {
        downloadJob?.cancel(); downloads.cancel(); ready=null; readyPolicy=null
        File(context.filesDir,"updates").listFiles()?.forEach { it.delete() }
        mutable.update { it.copy(phase=UpdatePhase.IDLE,percent=null,error="Nedladdningen avbröts.") }
    }
    private fun failed(e: Exception) { Diagnostics.report(DiagnosticCode.UPDATE_FAILED, e); downloads.cancel(); ready=null; readyPolicy=null; File(context.filesDir,"updates").listFiles()?.forEach { it.delete() }; mutable.update { it.copy(phase=UpdatePhase.ERROR,error=message(e,"Uppdateringen kunde inte hämtas. Kontrollera internet och försök igen.")) } }
    private fun message(e: Exception, fallback: String): String = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: fallback else fallback
    companion object {
        // Only applicationContext is retained; no Activity/View is captured.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppUpdateManager? = null
        fun get(context: Context): AppUpdateManager = instance ?: synchronized(this) { instance ?: AppUpdateManager(context.applicationContext).also { instance=it } }
    }
}
