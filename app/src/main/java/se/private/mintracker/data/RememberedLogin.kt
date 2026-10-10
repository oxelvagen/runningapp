package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.status.SessionStatus

/** RefreshFailure is retryable; NotAuthenticated includes explicit logout and revoked tokens.
 * This only grants access to local app data. Server requests still require a valid JWT and RLS.
 * The SDK continues refreshing and replaces this temporary state when the network recovers.
 */
fun rememberedUserId(status: SessionStatus): String? = when (status) {
    is SessionStatus.Authenticated -> status.session.user?.id
    is SessionStatus.RefreshFailure -> SecureSessions.store.offlineOwner()
    else -> null
}
