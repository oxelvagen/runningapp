package se.oxelv.mintracker.data

import android.app.Application
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MinTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SecureSessions.initialize(this)
    }
}

object SecureSessions {
    lateinit var store: SecureSessionManager
        private set
    fun initialize(context: Context) { store = SecureSessionManager(context.applicationContext) }
}

/** One canonical session. Its random data key is wrapped by an Android Keystore key.
 * In biometric mode only a successful per-use CryptoObject can unwrap it after process death.
 * The unlocked data key remains in RAM for token rotation and ongoing background GPS/sync.
 */
class SecureSessionManager(context: Context, private val storageName: String = "secure_auth", private val migrateLegacy: Boolean = true,
    private val legacyManager: () -> SessionManager = {
        // SDK 3.2.3 createDefaultSessionManager uses this project-qualified key;
        // SettingsSessionManager also migrates the earlier unqualified "session" key.
        val projectSession = SettingsSessionManager(key = "sb-${SUPABASE_PROJECT_URL.removeSuffix("/").replace('/', '-').replace('.', '-')}-session")
        object : SessionManager by projectSession {
            override suspend fun deleteSession() {
                projectSession.deleteSession()
                SettingsSessionManager().deleteSession()
            }
        }
    }) : SessionManager {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val biometricAlias = "$storageName.biometric.v1"
    private val normalAlias = "$storageName.session.v1"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var dataKey: ByteArray? = null
    val biometricEnabled: Boolean @Synchronized get() = prefs.getBoolean("biometric", false)
    val owner: String? @Synchronized get() = prefs.getString("owner", null)
    val hasSession: Boolean @Synchronized get() = prefs.contains("session")

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun bytes(key: String) = Base64.decode(checkNotNull(prefs.getString(key, null)), Base64.NO_WRAP)
    private fun key(biometric: Boolean): SecretKey {
        val alias = if (biometric) biometricAlias else normalAlias
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(biometric).apply {
                    if (biometric) {
                        setInvalidatedByBiometricEnrollment(true)
                        if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        else setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }.build())
        }.generateKey()
    }
    private fun encryptCipher(secret: SecretKey) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
    private fun decryptCipher(secret: SecretKey, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
    }
    @Synchronized private fun write(session: UserSession) {
        val userId = requireNotNull(session.user?.id) { "Session has no verified owner" }
        if (owner != null && owner != userId) clear()
        var newWrapper: Cipher? = null
        if (dataKey == null) {
            check(!biometricEnabled) { "Biometric session is locked" }
            dataKey = if (prefs.contains("wrapped")) decryptCipher(key(false), bytes("wrap_iv")).doFinal(bytes("wrapped"))
                else KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded.also { newWrapper = encryptCipher(key(false)) }
        }
        val sessionCipher = encryptCipher(SecretKeySpec(dataKey, "AES"))
        val encrypted = sessionCipher.doFinal(json.encodeToString(UserSession.serializer(), session).toByteArray(Charsets.UTF_8))
        val edit = prefs.edit().putString("session", encode(encrypted)).putString("iv", encode(sessionCipher.iv))
            .putString("owner", userId).putBoolean("migrated", true)
        newWrapper?.let { edit.putString("wrapped", encode(it.doFinal(dataKey))).putString("wrap_iv", encode(it.iv)) }
        check(edit.commit()) { "Could not persist session" }
    }
    @Synchronized private fun read(): UserSession? {
        if (!hasSession || (biometricEnabled && dataKey == null)) return null
        if (dataKey == null) dataKey = decryptCipher(key(false), bytes("wrap_iv")).doFinal(bytes("wrapped"))
        val clear = decryptCipher(SecretKeySpec(dataKey, "AES"), bytes("iv")).doFinal(bytes("session"))
        return json.decodeFromString(UserSession.serializer(), clear.toString(Charsets.UTF_8)).also {
            check(it.user?.id == owner) { "Session owner mismatch" }
        }
    }
    override suspend fun saveSession(session: UserSession) { write(session) }
    override suspend fun loadSession(): UserSession? {
        if (migrateLegacy && !prefs.getBoolean("migrated", false)) {
            val legacy = legacyManager()
            val old = legacy.loadSession()
            if (old != null) write(old)
            legacy.deleteSession()
            check(prefs.edit().putBoolean("migrated", true).commit())
        }
        return try { read() } catch (_: Exception) { clear(); null }
    }
    override suspend fun deleteSession() { clear(); if (migrateLegacy) legacyManager().deleteSession() }
    @Synchronized fun clear() {
        dataKey?.fill(0); dataKey = null
        check(prefs.edit().clear().putBoolean("migrated", true).commit())
        // A lost or invalidated biometric key must never prevent ordinary login.
        keyStore.deleteEntry(biometricAlias)
    }
    @Synchronized fun enableCipher(): Cipher {
        check(hasSession && dataKey != null && !biometricEnabled)
        return encryptCipher(key(true))
    }
    @Synchronized fun unlockCipher(): Cipher {
        check(biometricEnabled && hasSession)
        return decryptCipher(key(true), bytes("wrap_iv"))
    }
    @Synchronized fun enable(cipher: Cipher, expectedOwner: String) {
        check(owner == expectedOwner && !biometricEnabled)
        val wrapped = cipher.doFinal(checkNotNull(dataKey))
        check(prefs.edit().putString("wrapped", encode(wrapped)).putString("wrap_iv", encode(cipher.iv))
            .putBoolean("biometric", true).commit())
    }
    @Synchronized fun unlock(cipher: Cipher, expectedOwner: String): UserSession {
        check(owner == expectedOwner && biometricEnabled)
        dataKey?.fill(0)
        dataKey = cipher.doFinal(bytes("wrapped"))
        return checkNotNull(read())
    }
    @Synchronized fun disable(expectedOwner: String) {
        check(owner == expectedOwner)
        val cipher = encryptCipher(key(false))
        val wrapped = cipher.doFinal(checkNotNull(dataKey))
        check(prefs.edit().putString("wrapped", encode(wrapped)).putString("wrap_iv", encode(cipher.iv))
            .putBoolean("biometric", false).commit())
        keyStore.deleteEntry(biometricAlias)
    }
}
