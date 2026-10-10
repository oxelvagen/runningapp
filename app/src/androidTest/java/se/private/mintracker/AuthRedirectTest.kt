package se.oxelv.mintracker

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.data.AuthRedirect

@RunWith(AndroidJUnit4::class)
class AuthRedirectTest {
    @Test fun recoveryIsNeverImportedAsOrdinarySignIn() {
        assertEquals(AuthRedirect.Callback.Recovery, AuthRedirect.classify(Uri.parse(
            "${AuthRedirect.URL}#access_token=sample&refresh_token=sample&type=recovery")))
        assertTrue(AuthRedirect.classify(Uri.parse("${AuthRedirect.URL}#type=recovery&access_token=sample")) is AuthRedirect.Callback.Failure)
        assertEquals(AuthRedirect.Callback.Session, AuthRedirect.classify(Uri.parse(
            "${AuthRedirect.URL}#access_token=sample&refresh_token=sample&type=magiclink")))
    }
    @Test fun registrationCallbackMatchesManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AuthRedirect.URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(context.packageName)
        }
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(context.packageManager)?.className)
    }

    @Test fun classifiesConfirmationAndSwedishFailuresWithoutRetainingSecrets() {
        assertEquals(AuthRedirect.Callback.Session, AuthRedirect.classify(
            Uri.parse("${AuthRedirect.URL}#access_token=sample&refresh_token=sample&type=signup")))
        val expired = AuthRedirect.classify(Uri.parse("${AuthRedirect.URL}#error=access_denied&error_code=otp_expired"))
        assertTrue(expired is AuthRedirect.Callback.Failure)
        assertTrue((expired as AuthRedirect.Callback.Failure).message.contains("gått ut"))
        assertTrue(AuthRedirect.classify(Uri.parse(AuthRedirect.URL)) is AuthRedirect.Callback.Failure)
        assertEquals(AuthRedirect.Callback.Unrelated, AuthRedirect.classify(Uri.parse("mintracker://else/callback#access_token=sample")))
        assertEquals(AuthRedirect.Callback.Unrelated, AuthRedirect.classify(Uri.parse("https://localhost:3000")))
    }
}
