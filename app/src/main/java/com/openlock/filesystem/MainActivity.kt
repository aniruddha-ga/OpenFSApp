package com.openlock.filesystem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openlock.filesystem.auth.AuthManager
import com.openlock.filesystem.auth.VaultSessionState
import com.openlock.filesystem.data.VaultRepository
import com.openlock.filesystem.ui.auth.AuthScreen
import com.openlock.filesystem.ui.auth.CreateVaultDialog
import com.openlock.filesystem.ui.auth.RestoreVaultDialog
import com.openlock.filesystem.ui.auth.VaultUnlockScreen
import com.openlock.filesystem.ui.explorer.VaultExplorerScreen
import com.openlock.filesystem.ui.preview.FilePreviewScreen
import com.openlock.filesystem.ui.settings.SettingsScreen
import com.openlock.filesystem.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : FragmentActivity() {
    private lateinit var authManager: AuthManager
    private lateinit var vaultRepository: VaultRepository

    @Volatile
    private var lastInteractionTimeMs: Long = System.currentTimeMillis()

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTimeMs = System.currentTimeMillis()
    }

    fun getLastInteractionTimeMs(): Long = lastInteractionTimeMs

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && !authManager.isPickingFile) {
                Log.d("SessionManager", "Screen OFF detected. Auto-locking vault session.")
                vaultRepository.clearTempPreviewCache()
                authManager.lock()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enforce Window FLAG_SECURE to prevent screenshots & recents thumbnail leakage
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        authManager = AuthManager(applicationContext)
        vaultRepository = VaultRepository(applicationContext)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenLockApp(
                        activity = this@MainActivity,
                        authManager = authManager,
                        vaultRepository = vaultRepository
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenOffReceiver, filter)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
        if (!authManager.isPickingFile) {
            vaultRepository.clearTempPreviewCache()
            authManager.lock()
        }
    }
}

@Composable
fun OpenLockApp(
    activity: MainActivity,
    authManager: AuthManager,
    vaultRepository: VaultRepository
) {
    val isAppUnlocked by authManager.isAppUnlocked.collectAsState()
    val vaultState by authManager.vaultState.collectAsState()
    val selectedVaultName by authManager.selectedVaultName.collectAsState()
    val navController = rememberNavController()

    var showCreateVaultDialog by remember { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Ask for all Manifest runtime permissions on first launch
    val requiredPermissions = remember { com.openlock.filesystem.crypto.SecurityUtils.getRequiredManifestPermissions() }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(activity, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Folder navigation path stack: List of Pair(folderId, folderName)
    var folderPathStack by remember {
        mutableStateOf(listOf<Pair<String?, String>>(null to "Open FS"))
    }

    // Auto-Lock Inactivity Monitor
    LaunchedEffect(isAppUnlocked, vaultState) {
        while (true) {
            delay(5000L.milliseconds)
            val timeoutMinutes = authManager.getAutoLockTimeoutMinutes()
            if (timeoutMinutes > 0 && authManager.isAppUnlocked.value && !authManager.isPickingFile) {
                val idleMs = System.currentTimeMillis() - activity.getLastInteractionTimeMs()
                val maxIdleMs = timeoutMinutes * 60 * 1000L
                if (idleMs >= maxIdleMs) {
                    Log.d("SessionManager", "Inactivity timeout reached ($timeoutMinutes min). Auto-locking vault.")
                    vaultRepository.clearTempPreviewCache()
                    authManager.lock()
                }
            }
        }
    }

    // Auto-Lock Vault on App Backgrounding (ON_STOP)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!authManager.isPickingFile) {
                    vaultRepository.clearTempPreviewCache()
                    authManager.lock()
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                // Reset file picking flag when app resumes
                authManager.isPickingFile = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!isAppUnlocked) {
        val isSetupMode = !authManager.isAppLockSetup()
        AuthScreen(
            isSetupMode = isSetupMode,
            isBiometricAvailable = authManager.isBiometricEnabled(),
            getRemainingLockoutSeconds = { authManager.getRemainingLockoutSeconds() },
            getFailedAttemptsCount = { authManager.getFailedAttemptsCount() },
            onPinSubmitted = { pin -> authManager.unlockAppLock(pin) },
            onBiometricRequested = {
                showBiometricPrompt(activity) { authManager.unlockWithBiometric() }
            },
            onSetupComplete = { newPin -> authManager.setupAppLockPin(newPin) },
            title = if (isSetupMode) "Create App Lock PIN" else "Enter App Lock PIN",
            subtitle = "Unlocks the Open FS application UI"
        )
    } else {
        // Show Create Vault Popup if no vault exists OR user requested to create a new vault file
        if (!authManager.hasAnyVaultSetup() || showCreateVaultDialog) {
            CreateVaultDialog(
                authManager = authManager,
                onDismiss = {
                    if (authManager.hasAnyVaultSetup()) {
                        showCreateVaultDialog = false
                    }
                },
                onCreateVault = { name, password ->
                    authManager.setupNewVault(name, password)
                    showCreateVaultDialog = false
                },
                onRestoreVaultRequested = { uri ->
                    authManager.isPickingFile = true
                    selectedRestoreUri = uri
                }
            )
        }

        selectedRestoreUri?.let { uri ->
            RestoreVaultDialog(
                sourceUri = uri,
                authManager = authManager,
                vaultRepository = vaultRepository,
                onDismiss = { selectedRestoreUri = null },
                onRestoreSuccess = {
                    selectedRestoreUri = null
                    showCreateVaultDialog = false
                }
            )
        }

        when (val vState = vaultState) {
            is VaultSessionState.Uninitialized, is VaultSessionState.Locked -> {
                VaultUnlockScreen(
                    getRemainingLockoutSeconds = { authManager.getRemainingLockoutSeconds() },
                    getFailedAttemptsCount = { authManager.getFailedAttemptsCount() },
                    onPasswordSubmitted = { password -> authManager.unlockVaultMaster(password, selectedVaultName) },
                    title = "Unlock Vault: $selectedVaultName.ofs",
                    subtitle = "Enter Master Password for this encrypted vault container file",
                    availableVaults = authManager.getAvailableVaults(),
                    selectedVaultName = selectedVaultName,
                    onSelectVault = { vault -> authManager.selectVault(vault) },
                    onCreateVaultRequested = { showCreateVaultDialog = true }
                )
            }
            is VaultSessionState.Unlocked -> {
                val masterKey = vState.fileSystemMasterKey

                NavHost(navController = navController, startDestination = "explorer") {
                    composable("explorer") {
                        val currentFolderPair = folderPathStack.last()
                        VaultExplorerScreen(
                            vaultRepository = vaultRepository,
                            masterKey = masterKey,
                            currentFolderId = currentFolderPair.first,
                            folderPath = folderPathStack,
                            authManager = authManager,
                            onFolderSelected = { folderId, folderName ->
                                if (folderId == null) {
                                    folderPathStack = listOf(null to "Open FS")
                                } else {
                                    val existingIndex = folderPathStack.indexOfFirst { it.first == folderId }
                                    folderPathStack = if (existingIndex != -1) {
                                        folderPathStack.subList(0, existingIndex + 1)
                                    } else {
                                        folderPathStack + (folderId to folderName)
                                    }
                                }
                            },
                            onNavigateBack = {
                                if (folderPathStack.size > 1) {
                                    folderPathStack = folderPathStack.dropLast(1)
                                }
                            },
                            onFilePreviewRequested = { itemId ->
                                navController.navigate("preview/$itemId")
                            },
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }

                    composable(
                        route = "preview/{itemId}",
                        arguments = listOf(navArgument("itemId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getString("itemId") ?: ""
                        FilePreviewScreen(
                            itemId = itemId,
                            vaultRepository = vaultRepository,
                            masterKey = masterKey,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            authManager = authManager,
                            vaultRepository = vaultRepository,
                            onNavigateBack = { navController.popBackStack() },
                            onLockRequested = {
                                vaultRepository.clearTempPreviewCache()
                                authManager.lock()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val biometricPrompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Biometric Unlock")
        .setSubtitle("Authenticate using your fingerprint or face to unlock Open FS")
        .setNegativeButtonText("Use PIN")
        .build()

    biometricPrompt.authenticate(promptInfo)
}
