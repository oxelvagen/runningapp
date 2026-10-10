package se.oxelv.mintracker.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import se.oxelv.mintracker.BuildConfig

@Composable
fun AppUpdateSettings(manager: AppUpdateManager) {
    val state by manager.state.collectAsState()
    val requirement = UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(),state.policy)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Appversion och uppdateringar",style=MaterialTheme.typography.titleMedium)
        Text("Installerad version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Senaste tillgängliga version: ${state.policy?.versionName ?: "Inte känd"}")
        Text(when(requirement) {
            UpdateRequirement.CURRENT -> if (state.checkError == null) "Uppdaterad" else "Senast kontrollerad version matchar appen"
            UpdateRequirement.OPTIONAL -> "Uppdatering finns"
            UpdateRequirement.REQUIRED -> "Uppdatering krävs"
            else -> "Versionsstatus kunde inte verifieras"
        })
        state.checkError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if (state.checkedAt > 0) Text("Senast verifierad: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(state.checkedAt))}",style=MaterialTheme.typography.bodySmall)
        Button(onClick={ manager.check(true) },enabled=!state.checking) { Text(if(state.checking) "Kontrollerar…" else "Sök efter uppdateringar") }
        if (requirement in listOf(UpdateRequirement.OPTIONAL,UpdateRequirement.REQUIRED)) TextButton(onClick=manager::show) { Text("Visa uppdatering") }
    } }
}

/** Wraps all ordinary content, including authentication. Forced policy has no admin exemption. */
@Composable
fun AppUpdateHost(manager: AppUpdateManager, unsavedTraining: Boolean, content: @Composable () -> Unit) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val required = UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(),state.policy)==UpdateRequirement.REQUIRED
    var openingInstaller by remember { mutableStateOf(false) }
    var waitingForPermission by remember { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { manager.installerReturned() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForPermission=false
        if (!context.packageManager.canRequestPackageInstalls()) manager.installError(IllegalStateException("Tillåt installation från Min Tracker för att fortsätta. Du kan försöka igen."))
        // User presses Install again after returning; permission never silently starts installation.
    }
    fun install() {
        if (openingInstaller) return
        if (unsavedTraining) { manager.installError(IllegalStateException("Avsluta och spara träningspasset före installationen.")); return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            try { waitingForPermission=true; permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))) }
            catch (e: Exception) { waitingForPermission=false; manager.installError(e) }
        } else scope.launch {
            openingInstaller=true
            try { val intent=manager.installIntent(unsavedTraining); installer.launch(intent); manager.installerOpened() }
            catch (e: Exception) { manager.installError(e) }
            finally { openingInstaller=false }
        }
    }
    val busy = state.phase in listOf(UpdatePhase.RESOLVING,UpdatePhase.DOWNLOADING,UpdatePhase.VERIFYING) || openingInstaller
    val installReady = state.phase in listOf(UpdatePhase.READY,UpdatePhase.INSTALLER)
    val action = {
        if (installReady) install() else manager.download()
    }
    if (UpdateRules.block(BuildConfig.VERSION_CODE.toLong(),state.policy,unsavedTraining)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Min Tracker behöver uppdateras",style=MaterialTheme.typography.headlineMedium)
            Text("Den här versionen stöds inte längre. Uppdatera till den senaste versionen för att fortsätta.")
            UpdateBody(state)
            Button(onClick=action,enabled=!busy && !waitingForPermission,modifier=Modifier.fillMaxWidth()) {
                Text(if(installReady) "Installera uppdatering" else "Uppdatera nu")
            }
            if (state.phase==UpdatePhase.DOWNLOADING) TextButton(onClick=manager::cancelDownload) { Text("Avbryt nedladdning") }
            TextButton(onClick={manager.check(true)},enabled=!state.checking) { Text("Sök igen") }
        } }
    } else {
        Column(Modifier.fillMaxSize()) {
            if (required && unsavedTraining) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                Text("Appen behöver uppdateras. Avsluta och spara ditt pågående pass först; registreringen fortsätter.",Modifier.padding(12.dp))
            }
            Box(Modifier.weight(1f)) { content() }
        }
        if (state.prompt && !unsavedTraining && state.policy != null &&
            UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(),state.policy)==UpdateRequirement.OPTIONAL) {
            AlertDialog(onDismissRequest=manager::dismiss,
                properties=DialogProperties(dismissOnBackPress=true,dismissOnClickOutside=false),
                title={Text("Ny version av Min Tracker!")},
                text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {UpdateBody(state)}},
                confirmButton={TextButton(onClick=action,enabled=!busy && !waitingForPermission) {Text(if(installReady) "Installera uppdatering" else "Uppdatera nu")}},
                dismissButton={Column {
                    if(state.phase==UpdatePhase.DOWNLOADING) TextButton(onClick=manager::cancelDownload) {Text("Avbryt nedladdning")}
                    TextButton(onClick=manager::dismiss) {Text("Senare")}
                }})
        }
    }
}

@Composable private fun UpdateBody(state: AppUpdateState) {
    Text("Version ${state.policy?.versionName.orEmpty()} finns tillgänglig.")
    if (!state.policy?.notes.isNullOrBlank()) {Text("Nyheter",style=MaterialTheme.typography.titleSmall); Text(state.policy!!.notes)}
    when(state.phase) {
        UpdatePhase.RESOLVING -> { Text("Hämtar uppdateringen från GitHub…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        UpdatePhase.DOWNLOADING -> { Text("Laddar ner uppdatering… ${state.percent?.let { "$it %" } ?: ""}");
            LinearProgressIndicator(progress={ (state.percent ?: 0)/100f },modifier=Modifier.fillMaxWidth()) }
        UpdatePhase.VERIFYING -> {Text("Verifierar uppdateringen…"); LinearProgressIndicator(Modifier.fillMaxWidth())}
        UpdatePhase.READY -> Text("Uppdateringen är redo. Android ber dig godkänna installationen.")
        UpdatePhase.INSTALLER -> Text("Slutför installationen i Androids installationsdialog.")
        else -> Unit
    }
    state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    state.checkError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if (state.phase==UpdatePhase.ERROR) Text("Tryck Uppdatera nu för att försöka igen.")
}
