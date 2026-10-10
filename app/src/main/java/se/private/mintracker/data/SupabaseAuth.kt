package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

// The publishable key identifies the project; it is safe to include in the Android client.
const val SUPABASE_PROJECT_URL = "https://xbvifoucwpgnybtpwyrk.supabase.co"
const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_OAIeOaKlskUV-3So85QrOQ_XqCK41pi"
val supabase = createSupabaseClient(
    supabaseUrl = SUPABASE_PROJECT_URL,
    supabaseKey = SUPABASE_PUBLISHABLE_KEY
) {
    install(Auth) {
        scheme = "mintracker"
        host = "auth"
        autoLoadFromStorage = true
        autoSaveToStorage = true
        sessionManager = SecureSessions.store
    }
    install(Postgrest)
    install(Storage)
}
