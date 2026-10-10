package se.oxelv.mintracker

import android.content.Context
import android.content.ContextWrapper
import android.util.Patterns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.oxelv.mintracker.data.*

internal tailrec fun Context.authActivity(): FragmentActivity = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.authActivity()
    else -> error("Biometric authentication needs an Activity")
}

@Composable
fun ForgotPasswordScreen(initialEmail: String, repository: AuthRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(seconds) { if (seconds > 0) { delay(1000); seconds-- } }
    AuthColumn {
        Text("Återställ lösenord", style = MaterialTheme.typography.headlineMedium)
        Text("Vi skickar en länk som låter dig välja ett nytt lösenord.", color = AppColors.Muted)
        OutlinedTextField(email, { email = it }, enabled = !busy, label = { Text("E-post") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) message = "Ange en giltig e-postadress."
            else { busy = true; scope.launch {
                try {
                    repository.resetPassword(email)
                    message = "Om det finns ett konto med adressen skickas en återställningslänk. Kontrollera även skräpposten."
                    seconds = 60
                } catch (error: Exception) {
                    message = authErrorMessage(error)
                    seconds = 60
                } finally { busy = false }
            } }
        }, enabled = !busy && seconds == 0, modifier = Modifier.fillMaxWidth()) {
            Text(if (seconds > 0) "Försök igen om $seconds s" else "Skicka återställningslänk")
        }
        if (busy) CircularProgressIndicator()
        message?.let { Text(it) }
        TextButton(onClick = onBack, enabled = !busy) { Text("Tillbaka till inloggning") }
    }
}

@Composable private fun AuthColumn(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            AppCard(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
        }
    }
}

/** Hosts leave the root's Room/GPS/unsaved-summary state alive while hiding account content. */
@Composable
fun AuthSecurityHost(unsavedTraining: Boolean, registrationEnabled: Boolean, content: @Composable () -> Unit) {
    val recovery by PasswordRecovery.state.collectAsStateWithLifecycle()
    val security by AppSecurity.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (recovery.visible) {
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        AuthColumn {
            Text(if (recovery.completed) "Lösenordet har sparats" else "Välj nytt lösenord", style = MaterialTheme.typography.headlineMedium)
            if (recovery.ready) {
                Text(PasswordRules.description, color = AppColors.Muted)
                OutlinedTextField(password, { password = it }, enabled = !recovery.busy, label = { Text("Nytt lösenord") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirmation, { confirmation = it }, enabled = !recovery.busy, label = { Text("Bekräfta nytt lösenord") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { PasswordRecovery.save(password, confirmation); if (PasswordRecovery.state.value.completed) { password = ""; confirmation = "" } } },
                    enabled = !recovery.busy, modifier = Modifier.fillMaxWidth()) { Text("Spara nytt lösenord") }
            }
            if (recovery.busy) CircularProgressIndicator()
            recovery.message?.let { Text(it) }
            if (recovery.completed && unsavedTraining) Text("Ditt pågående pass finns kvar. Spara det innan du loggar ut och loggar in igen.")
            TextButton(onClick = { scope.launch {
                if (recovery.completed && !unsavedTraining) { runCatching { AuthRepository().signOut() } }
                PasswordRecovery.dismiss()
            } }, enabled = !recovery.busy) { Text(if (unsavedTraining) "Tillbaka till passet" else "Tillbaka till inloggning") }
        }
    } else if (security.locked) {
        AuthScreen(registrationEnabled = registrationEnabled, biometric = true)
    } else {
        content()
        val offer = security.offerFor
        if (offer != null && supabase.auth.currentUserOrNull()?.id == offer && !unsavedTraining) {
            AlertDialog(onDismissRequest = { AppSecurity.decline(context, offer) }, title = { Text("Vill du aktivera snabb inloggning?") },
                text = { Text("Använd fingeravtryck eller ansiktsigenkänning för att öppna Min Tracker nästa gång.") },
                confirmButton = { TextButton(onClick = { scope.launch { AppSecurity.change(context.authActivity(), true) } }, enabled = !security.busy) { Text("Aktivera") } },
                dismissButton = { TextButton(onClick = { AppSecurity.decline(context, offer) }) { Text("Inte nu") } })
        }
    }
}

@Composable
fun SecuritySettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppSecurity.state.collectAsStateWithLifecycle()
    // Reading the revision makes successful key changes immediately visible.
    val enabled = remember(state.revision) { SecureSessions.store.biometricEnabled }
    AppCard(Modifier.fillMaxWidth()) {
        Text("Säkerhet", style = MaterialTheme.typography.titleMedium)
        if (AppSecurity.supported(context) || enabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Biometrisk inloggning"); Text(if (enabled) "På" else "Av", color = AppColors.Muted) }
                Switch(enabled, onCheckedChange = { desired -> scope.launch { AppSecurity.change(context.authActivity(), desired) } }, enabled = !state.busy)
            }
            Text("Skyddar den sparade sessionen på den här telefonen. Lösenord krävs efter utloggning.", color = AppColors.Muted)
        } else Text("Säker biometri är inte tillgänglig. Lägg till fingeravtryck eller säker ansiktsigenkänning i telefonens inställningar.")
        if (state.busy) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
