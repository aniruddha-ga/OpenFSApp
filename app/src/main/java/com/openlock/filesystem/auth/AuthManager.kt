package com.openlock.filesystem.auth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openlock.filesystem.crypto.KeyManager
import com.openlock.filesystem.data.SingleFileContainerManager
import com.openlock.filesystem.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.crypto.SecretKey
import androidx.core.net.toUri
import androidx.core.content.edit

sealed class VaultSessionState {
    object Uninitialized : VaultSessionState()
    object Locked : VaultSessionState()
    data class Unlocked(val fileSystemMasterKey: SecretKey, val vaultName: String) : VaultSessionState()
}

/**
 * Dual-Lock Security Architecture:
 * 1. App Lock Layer (UI Lock): App Lock PIN / Biometric unlocks the App UI.
 *    - App Lock ALWAYS takes precedence on app launch/setup.
 * 2. Independent Vault Containers: Each vault file (<VaultName>.ofs) has its own independent salt, hash, and PBKDF2 key.
 *    - Vault creation/unlock is only presented AFTER App Lock is unlocked.
 * 3. ZERO secret keys are saved on ROM.
 */
class AuthManager(context: Context) {
    private val appContext = context.applicationContext
    val containerManager = SingleFileContainerManager(appContext)

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        "openlock_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Temporary flag to prevent auto-locking when external SAF File Pickers or Intent activities are active
    @Volatile
    var isPickingFile: Boolean = false

    // Session-level flag to ensure shred confirmation warning pops up at most ONCE per session
    @Volatile
    var hasPromptedShredWarningInSession: Boolean = false

    // App Lock (UI) state - ALWAYS starts locked so App Lock Screen shows first!
    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    // Selected Vault Name state
    private val _selectedVaultName = MutableStateFlow(
        getAvailableVaults().firstOrNull() ?: "PrimaryVault"
    )
    val selectedVaultName: StateFlow<String> = _selectedVaultName.asStateFlow()

    // Vault Master (File System) state
    private val _vaultState = MutableStateFlow<VaultSessionState>(
        if (hasAnyVaultSetup()) VaultSessionState.Locked else VaultSessionState.Uninitialized
    )
    val vaultState: StateFlow<VaultSessionState> = _vaultState.asStateFlow()

    fun isAppLockSetup(): Boolean = prefs.getBoolean(PREF_APP_LOCK_SETUP_COMPLETE, false)

    fun getAvailableVaults(): List<String> = containerManager.getAvailableVaults()

    fun hasAnyVaultSetup(): Boolean = getAvailableVaults().isNotEmpty()

    fun isVaultSetup(vaultName: String): Boolean {
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        return prefs.getBoolean("vault_${sanitized}_setup", false)
    }

    fun selectVault(vaultName: String) {
        _selectedVaultName.value = containerManager.sanitizeVaultName(vaultName)
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(PREF_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit(commit = true) { putBoolean(PREF_BIOMETRIC_ENABLED, enabled) }
    }

    fun getFailedAttemptsCount(): Int = prefs.getInt(PREF_FAILED_ATTEMPTS, 0)

    fun getRemainingLockoutSeconds(): Long {
        val lockoutUntil = prefs.getLong(PREF_LOCKOUT_UNTIL_MS, 0L)
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) {
            (lockoutUntil - now + 999) / 1000
        } else {
            0L
        }
    }

    private fun registerFailedAttempt() {
        val currentAttempts = prefs.getInt(PREF_FAILED_ATTEMPTS, 0) + 1

        if (currentAttempts >= 12) {
            wipeEntireVaultFileSystem()
            return
        }

        val now = System.currentTimeMillis()
        val lockoutDelayMs = when (currentAttempts) {
            1, 2 -> 0L        // Attempts 1-2: No delay
            3 -> 30_000L      // Attempt 3: 30 seconds
            4 -> 60_000L      // Attempt 4: 1 minute
            5 -> 120_000L     // Attempt 5: 2 minutes
            6 -> 300_000L     // Attempt 6: 5 minutes
            7 -> 900_000L     // Attempt 7: 15 minutes
            8 -> 1_800_000L   // Attempt 8: 30 minutes
            9 -> 3_600_000L   // Attempt 9: 1 hour
            else -> 86_400_000L // Attempts 10-11: 24 hours
        }

        prefs.edit(commit = true) {
            putInt(PREF_FAILED_ATTEMPTS, currentAttempts)
                .putLong(PREF_LOCKOUT_UNTIL_MS, now + lockoutDelayMs)
        }
    }

    private fun resetFailedAttempts() {
        prefs.edit(commit = true) {
            putInt(PREF_FAILED_ATTEMPTS, 0)
                .putLong(PREF_LOCKOUT_UNTIL_MS, 0L)
        }
    }

    fun wipeEntireVaultFileSystem() {
        containerManager.wipeContainers()

        val vaultStorageDir = File(appContext.filesDir, "encrypted_vault")
        if (vaultStorageDir.exists()) vaultStorageDir.deleteRecursively()

        val tempCacheDir = File(appContext.cacheDir, "vault_tmp")
        if (tempCacheDir.exists()) tempCacheDir.deleteRecursively()

        appContext.deleteDatabase("openlock_vault_db")
        prefs.edit(commit = true) { clear() }

        _isAppUnlocked.value = false
        _vaultState.value = VaultSessionState.Uninitialized
    }

    // --- App Lock (UI) Management ---
    fun setupAppLockPin(pin: String) {
        val salt = KeyManager.generateSalt()
        val pinHash = KeyManager.hashPin(pin, salt)

        prefs.edit(commit = true) {
            putBoolean(PREF_APP_LOCK_SETUP_COMPLETE, true)
                .putString(PREF_APP_LOCK_SALT_BASE64, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(
                    PREF_APP_LOCK_HASH_BASE64,
                    Base64.encodeToString(pinHash, Base64.NO_WRAP)
                )
        }

        _isAppUnlocked.value = true
    }

    fun checkSecurityThreats(checkEmulator: Boolean = false): Boolean {
        return com.openlock.filesystem.crypto.SecurityUtils.isDebuggerAttached() ||
                com.openlock.filesystem.crypto.SecurityUtils.isFridaOrHookingDetected() ||
                com.openlock.filesystem.crypto.SecurityUtils.isDeviceRooted() ||
                (checkEmulator && com.openlock.filesystem.crypto.SecurityUtils.isEmulator())
    }

    fun unlockAppLock(pin: String): Boolean {
        if (checkSecurityThreats()) {
            registerFailedAttempt()
            return false
        }
        if (!isAppLockSetup()) {
            _isAppUnlocked.value = true
            return true
        }
        if (getRemainingLockoutSeconds() > 0) return false

        val saltBase64 = prefs.getString(PREF_APP_LOCK_SALT_BASE64, null) ?: return false
        val hashBase64 = prefs.getString(PREF_APP_LOCK_HASH_BASE64, null) ?: return false

        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val storedHash = Base64.decode(hashBase64, Base64.NO_WRAP)

        val computedHash = KeyManager.hashPin(pin, salt)
        return if (computedHash.contentEquals(storedHash)) {
            resetFailedAttempts()
            _isAppUnlocked.value = true
            true
        } else {
            registerFailedAttempt()
            false
        }
    }

    fun changeAppLockPin(oldPin: String, newPin: String): Boolean {
        if (!unlockAppLock(oldPin)) return false
        setupAppLockPin(newPin)
        return true
    }

    // --- Dynamic Named Vault Container Management ---
    fun setupNewVault(vaultName: String, password: String): SecretKey {
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        val salt = KeyManager.generateSalt()
        val passwordHash = KeyManager.hashPassword(password, salt)
        val fileSystemKey = KeyManager.deriveKeyFromPassword(password.toCharArray(), salt)

        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashBase64 = Base64.encodeToString(passwordHash, Base64.NO_WRAP)

        prefs.edit(commit = true) {
            putBoolean("vault_${sanitized}_setup", true)
                .putString("vault_${sanitized}_salt_base64", saltBase64)
                .putString("vault_${sanitized}_hash_base64", hashBase64)
        }

        containerManager.currentSaltBase64 = saltBase64
        containerManager.currentHashBase64 = hashBase64
        _selectedVaultName.value = sanitized
        containerManager.openSession(sanitized)
        containerManager.autoSyncUri = if (isAutoSyncEnabled(sanitized)) getAutoSyncUri(sanitized) else null
        resetFailedAttempts()
        _vaultState.value = VaultSessionState.Unlocked(fileSystemKey, sanitized)
        return fileSystemKey
    }

    fun unlockVaultMaster(password: String, vaultName: String = _selectedVaultName.value): Boolean {
        if (checkSecurityThreats()) {
            registerFailedAttempt()
            return false
        }
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        if (getRemainingLockoutSeconds() > 0) return false

        val masterFile = containerManager.getMasterContainerFile(sanitized)

        if (!isVaultSetup(sanitized)) {
            if (masterFile.exists() && masterFile.length() > 0) {
                val meta = containerManager.readVaultMetadataFromZip(masterFile)
                val salt: ByteArray
                val storedHash: ByteArray?

                if (meta != null) {
                    salt = Base64.decode(meta.saltBase64, Base64.NO_WRAP)
                    storedHash = Base64.decode(meta.hashBase64, Base64.NO_WRAP)
                    val computedHash = KeyManager.hashPassword(password, salt)
                    if (!computedHash.contentEquals(storedHash)) {
                        registerFailedAttempt()
                        return false
                    }
                } else {
                    salt = KeyManager.generateSalt()
                    storedHash = null
                }

                val fileSystemKey = KeyManager.deriveKeyFromPassword(password.toCharArray(), salt)
                val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
                val passwordHash = storedHash ?: KeyManager.hashPassword(password, salt)
                val hashBase64 = Base64.encodeToString(passwordHash, Base64.NO_WRAP)

                prefs.edit(commit = true) {
                    putBoolean("vault_${sanitized}_setup", true)
                        .putString("vault_${sanitized}_salt_base64", saltBase64)
                        .putString("vault_${sanitized}_hash_base64", hashBase64)
                }

                containerManager.currentSaltBase64 = saltBase64
                containerManager.currentHashBase64 = hashBase64
                _selectedVaultName.value = sanitized
                containerManager.openSession(sanitized)
                containerManager.autoSyncUri = if (isAutoSyncEnabled(sanitized)) getAutoSyncUri(sanitized) else null
                resetFailedAttempts()
                _vaultState.value = VaultSessionState.Unlocked(fileSystemKey, sanitized)
                return true
            } else {
                return false
            }
        }

        try {
            val saltBase64 = prefs.getString("vault_${sanitized}_salt_base64", null) ?: return false
            val hashBase64 = prefs.getString("vault_${sanitized}_hash_base64", null) ?: return false

            val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
            val storedHash = Base64.decode(hashBase64, Base64.NO_WRAP)

            val computedHash = KeyManager.hashPassword(password, salt)
            if (!computedHash.contentEquals(storedHash)) {
                registerFailedAttempt()
                return false
            }

            val fileSystemKey = KeyManager.deriveKeyFromPassword(password.toCharArray(), salt)

            containerManager.currentSaltBase64 = saltBase64
            containerManager.currentHashBase64 = hashBase64
            _selectedVaultName.value = sanitized
            containerManager.openSession(sanitized)
            containerManager.autoSyncUri = if (isAutoSyncEnabled(sanitized)) getAutoSyncUri(sanitized) else null
            resetFailedAttempts()
            _vaultState.value = VaultSessionState.Unlocked(fileSystemKey, sanitized)
            return true
        } catch (_: Exception) {
            registerFailedAttempt()
            return false
        }
    }

    suspend fun restoreAndUnlockVault(
        context: Context,
        sourceUri: Uri,
        password: String,
        vaultRepository: VaultRepository
    ): Boolean {
        var nameFromUri: String? = null
        if (sourceUri.scheme == "content") {
            try {
                context.contentResolver.query(
                    sourceUri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            nameFromUri = cursor.getString(index)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val rawName = (nameFromUri ?: sourceUri.lastPathSegment ?: "RestoredVault")
            .removeSuffix(".ofs")
            .removeSuffix(".bak")
            .removeSuffix("_backup")
        val vaultName = containerManager.sanitizeVaultName(rawName)

        val metadata = containerManager.readVaultMetadataFromUri(context, sourceUri)
            ?: return false

        val salt = Base64.decode(metadata.saltBase64, Base64.NO_WRAP)
        val storedHash = Base64.decode(metadata.hashBase64, Base64.NO_WRAP)
        val computedHash = KeyManager.hashPassword(password, salt)

        if (!computedHash.contentEquals(storedHash)) {
            Log.e("AuthManager", "Password verification failed for container $vaultName")
            return false
        }

        val fileSystemKey = KeyManager.deriveKeyFromPassword(password.toCharArray(), salt)

        // Restore vault container zip to storage
        val success = vaultRepository.restoreVaultContainer(vaultName, sourceUri)
        if (!success) return false

        prefs.edit(commit = true) {
            putBoolean("vault_${vaultName}_setup", true)
                .putString("vault_${vaultName}_salt_base64", metadata.saltBase64)
                .putString("vault_${vaultName}_hash_base64", metadata.hashBase64)
        }

        containerManager.currentSaltBase64 = metadata.saltBase64
        containerManager.currentHashBase64 = metadata.hashBase64
        _selectedVaultName.value = vaultName
        containerManager.openSession(vaultName)
        containerManager.autoSyncUri = if (isAutoSyncEnabled(vaultName)) getAutoSyncUri(vaultName) else null
        resetFailedAttempts()
        _vaultState.value = VaultSessionState.Unlocked(fileSystemKey, vaultName)
        return true
    }

    // --- Accessible Storage Auto-Sync Preferences & Operations ---
    fun isAutoSyncEnabled(vaultName: String = _selectedVaultName.value): Boolean {
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        return prefs.getBoolean("auto_sync_${sanitized}_enabled", false)
    }

    fun setAutoSyncEnabled(vaultName: String = _selectedVaultName.value, enabled: Boolean) {
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        prefs.edit(commit = true) { putBoolean("auto_sync_${sanitized}_enabled", enabled) }
        containerManager.autoSyncUri = if (enabled) getAutoSyncUri(sanitized) else null
        if (enabled) {
            syncAccessibleStorageNow(sanitized)
        }
    }

    fun getAutoSyncUri(vaultName: String = _selectedVaultName.value): Uri? {
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        val uriString = prefs.getString("auto_sync_${sanitized}_uri", null) ?: return null
        return try {
            uriString.toUri() } catch (_: Exception) { null }
    }

    fun setAutoSyncUri(vaultName: String = _selectedVaultName.value, uri: Uri) {
        val sanitized = containerManager.sanitizeVaultName(vaultName)
        prefs.edit(commit = true) { putString("auto_sync_${sanitized}_uri", uri.toString()) }
        if (isAutoSyncEnabled(sanitized)) {
            containerManager.autoSyncUri = uri
            syncAccessibleStorageNow(sanitized)
        }
    }

    fun compareContainerHashes(vaultName: String = _selectedVaultName.value, externalUri: Uri): Boolean {
        val masterFile = containerManager.getMasterContainerFile(vaultName)
        val internalHash = containerManager.computeFileHash(masterFile)
        val externalHash = containerManager.computeUriHash(appContext, externalUri)
        return internalHash != null && externalHash != null && internalHash == externalHash
    }

    fun syncAccessibleStorageNow(vaultName: String = _selectedVaultName.value): Boolean {
        val uri = getAutoSyncUri(vaultName) ?: return false
        return containerManager.syncToExternalStorage(appContext, uri)
    }

    fun changeVaultMasterPassword(oldPassword: String, newPassword: String, vaultName: String = _selectedVaultName.value): Boolean {
        if (!unlockVaultMaster(oldPassword, vaultName)) return false
        setupNewVault(vaultName, newPassword)
        return true
    }

    fun unlockWithBiometric(): Boolean {
        if (!isBiometricEnabled()) return false
        _isAppUnlocked.value = true
        return true
    }

    fun enableBiometric() {
        prefs.edit(commit = true) { putBoolean(PREF_BIOMETRIC_ENABLED, true) }
    }

    fun getAutoLockTimeoutMinutes(): Int = prefs.getInt(PREF_AUTO_LOCK_TIMEOUT_MINUTES, 5)

    fun setAutoLockTimeoutMinutes(minutes: Int) {
        prefs.edit(commit = true) { putInt(PREF_AUTO_LOCK_TIMEOUT_MINUTES, minutes) }
    }

    fun isShredAfterImportEnabled(): Boolean = prefs.getBoolean(PREF_SHRED_AFTER_IMPORT_ENABLED, false)

    fun setShredAfterImportEnabled(enabled: Boolean) {
        prefs.edit(commit = true) { putBoolean(PREF_SHRED_AFTER_IMPORT_ENABLED, enabled) }
    }

    fun lock() {
        hasPromptedShredWarningInSession = false
        if (isAppLockSetup()) {
            _isAppUnlocked.value = false
        } else {
            _isAppUnlocked.value = false
        }
        if (hasAnyVaultSetup()) {
            containerManager.closeSession()
            val tempCacheDir = File(appContext.cacheDir, "vault_tmp")
            if (tempCacheDir.exists()) tempCacheDir.deleteRecursively()
            _vaultState.value = VaultSessionState.Locked
        }
    }

    companion object {
        private const val PREF_APP_LOCK_SETUP_COMPLETE = "app_lock_setup_complete"
        private const val PREF_APP_LOCK_SALT_BASE64 = "app_lock_salt_base64"
        private const val PREF_APP_LOCK_HASH_BASE64 = "app_lock_hash_base64"

        private const val PREF_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val PREF_FAILED_ATTEMPTS = "failed_attempts_count"
        private const val PREF_LOCKOUT_UNTIL_MS = "lockout_until_ms"
        private const val PREF_AUTO_LOCK_TIMEOUT_MINUTES = "auto_lock_timeout_minutes"
        private const val PREF_SHRED_AFTER_IMPORT_ENABLED = "shred_after_import_enabled"
    }
}
