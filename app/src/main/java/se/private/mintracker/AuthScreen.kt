package se.oxelv.mintracker

import android.util.Patterns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.oxelv.mintracker.data.AuthRepository
import se.oxelv.mintracker.data.authErrorMessage
import se.oxelv.mintracker.data.AppSecurity
import se.oxelv.mintracker.data.supabase
import io.github.jan.supabase.auth.auth
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(repository: AuthRepository = remember { AuthRepository() }, registrationEnabled: Boolean = true, biometric: Boolean = false) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val security by AppSecurity.state.collectAsStateWithLifecycle()
    var createAccount by remember { mutableStateOf(false) }
    LaunchedEffect(registrationEnabled) { if (!registrationEnabled) createAccount = false }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var forgot by remember { mutableStateOf(false) }
    if (forgot) { ForgotPasswordScreen(email, repository) { forgot = false }; return }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Min Tracker", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppColors.Navy)
        Text("Dina pass, samlade på ett ställe", color = AppColors.Muted)
        Spacer(Modifier.height(24.dp))
        if (biometric) {
            Text("Välkommen tillbaka!", style = MaterialTheme.typography.headlineSmall)
            if (AppSecurity.supported(context) && se.oxelv.mintracker.data.SecureSessions.store.biometricEnabled) OutlinedButton(onClick = { scope.launch { AppSecurity.unlock(context.authActivity()) } }, enabled = !security.busy && !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Logga in med fingeravtryck") }
            security.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (security.busy) CircularProgressIndicator()
        }
        Text(if (createAccount) "Skapa konto" else "Logga in", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (createAccount) {
            OutlinedTextField(displayName, { displayName = it; message = null }, label = { Text("Visningsnamn") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(email, { email = it; message = null }, label = { Text("E-post") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it; message = null }, label = { Text("Lösenord") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!createAccount) TextButton(onClick = { password = ""; forgot = true }, enabled = !busy && !security.busy) { Text("Glömt lösenord?") }
        if (createAccount) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(confirmPassword, { confirmPassword = it; message = null }, label = { Text("Bekräfta lösenord") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                message = when {
                    createAccount && displayName.isBlank() -> "Ange ett visningsnamn."
                    !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Ange en giltig e-postadress."
                    password.isBlank() -> "Ange ett lösenord."
                    createAccount && password.length < 6 -> "Lösenordet måste innehålla minst 6 tecken."
                    createAccount && password != confirmPassword -> "Lösenorden stämmer inte överens."
                    else -> null
                }
                if (message == null) {
                    busy = true
                    scope.launch {
                        try {
                            if (createAccount) {
                                val needsConfirmation = repository.signUp(displayName, email, password)
                                if (needsConfirmation) {
                                    message = "Kontot är skapat. Öppna länken i e-postmeddelandet för att verifiera adressen och komma tillbaka till appen."
                                    createAccount = false
                                    password = ""
                                    confirmPassword = ""
                                }
                            } else {
                                repository.signIn(email, password)
                                password = ""
                                supabase.auth.currentUserOrNull()?.id?.let { AppSecurity.passwordLogin(context, it) }
                            }
                        } catch (error: Exception) {
                            message = authErrorMessage(error)
                        } finally { busy = false }
                    }
                }
            },
            enabled = !busy && !security.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ) { Text(if (createAccount) "Skapa konto" else "Logga in", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
        message?.let { Text(it, color = if (it.startsWith("Kontot är skapat")) AppColors.Navy else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        if (!registrationEnabled) Text("Registrering är tillfälligt stängd.", Modifier.padding(top = 12.dp))
        TextButton(onClick = { createAccount = !createAccount; message = null; password = ""; confirmPassword = "" }, enabled = !busy && !security.busy && (registrationEnabled || createAccount),
            modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (createAccount) "Har du redan ett konto? Logga in" else "Ny här? Skapa konto")
        }
    }
}
