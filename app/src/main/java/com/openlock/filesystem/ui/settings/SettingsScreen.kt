package com.openlock.filesystem.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openlock.filesystem.auth.AuthManager
import com.openlock.filesystem.data.VaultRepository
import com.openlock.filesystem.ui.auth.RestoreVaultDialog
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: AuthManager,
    vaultRepository: VaultRepository,
    onNavigateBack: () -> Unit,
    onLockRequested: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isBiometricEnabled = remember { mutableStateOf(authManager.isBiometricEnabled()) }
    val totalFilesState = vaultRepository.getTotalFileCount().collectAsState(initial = 0)
    val totalSizeState = vaultRepository.getTotalVaultSize().collectAsState(initial = 0L)
    val selectedVaultName by authManager.selectedVaultName.collectAsState()

    var isAutoSyncChecked by remember { mutableStateOf(authManager.isAutoSyncEnabled(selectedVaultName)) }
    var showOverwriteWarningDialog by remember { mutableStateOf(false) }

    var showChangeAppLockDialog by remember { mutableStateOf(false) }
    var showChangeVaultMasterDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // SAF Accessible Auto-Sync Document Creator Launcher
    val autoSyncCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { newUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            authManager.setAutoSyncUri(selectedVaultName, newUri)
            authManager.setAutoSyncEnabled(selectedVaultName, true)
            isAutoSyncChecked = true
            statusMessage = "Auto-Sync enabled! Accessible storage file updated."
        }
    }

    // SAF Export Backup Launcher (for PC / External Device sharing)
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                val success = vaultRepository.exportVaultContainer(selectedVaultName, it)
                statusMessage = if (success) "Backup container ($selectedVaultName.ofs) exported successfully!" else "Failed to export backup container."
            }
        }
    }

    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // SAF Restore Backup Launcher (for reinstallation / device transfer)
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            authManager.isPickingFile = true
            selectedRestoreUri = it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Vault Statistics
            val containerFileSize = remember(selectedVaultName, totalFilesState.value, totalSizeState.value) {
                vaultRepository.getContainerFileSize(selectedVaultName)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Vault Statistics ($selectedVaultName.ofs)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Container File Size ($selectedVaultName.ofs): ${formatFileSize(containerFileSize)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Unencrypted Payload Data: ${formatFileSize(totalSizeState.value ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Total Encrypted Files: ${totalFilesState.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Auto-Lock Inactivity Timeout Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Auto-Lock Inactivity Timeout",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Automatically locks the vault when left idle/untouched in the foreground.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    var selectedTimeout by remember { mutableIntStateOf(authManager.getAutoLockTimeoutMinutes()) }
                    val timeoutOptions = listOf(
                        1 to "1 Minute",
                        3 to "3 Minutes",
                        5 to "5 Minutes (Default)",
                        10 to "10 Minutes",
                        15 to "15 Minutes",
                        30 to "30 Minutes"
                    )

                    timeoutOptions.forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTimeout = minutes
                                    authManager.setAutoLockTimeoutMinutes(minutes)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedTimeout == minutes),
                                onClick = {
                                    selectedTimeout = minutes
                                    authManager.setAutoLockTimeoutMinutes(minutes)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Accessible Storage Auto-Sync Options
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Auto-Sync to Accessible Storage", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (isAutoSyncChecked) "Updates accessible external backup file automatically"
                                    else "Vault stored only in inaccessible private app storage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isAutoSyncChecked,
                            onCheckedChange = { enable ->
                                if (!enable) {
                                    authManager.setAutoSyncEnabled(selectedVaultName, false)
                                    isAutoSyncChecked = false
                                    statusMessage = "Auto-Sync disabled. Operating strictly in private internal storage."
                                } else {
                                    val existingUri = authManager.getAutoSyncUri(selectedVaultName)
                                    if (existingUri == null) {
                                        authManager.isPickingFile = true
                                        autoSyncCreateLauncher.launch("${selectedVaultName}_accessible.ofs")
                                    } else {
                                        val hashesMatch = authManager.compareContainerHashes(selectedVaultName, existingUri)
                                        if (hashesMatch) {
                                            authManager.setAutoSyncEnabled(selectedVaultName, true)
                                            isAutoSyncChecked = true
                                            statusMessage = "Auto-Sync enabled! Container hashes match."
                                        } else {
                                            showOverwriteWarningDialog = true
                                        }
                                    }
                                }
                            }
                        )
                    }

                    if (isAutoSyncChecked) {
                        val currentUri = authManager.getAutoSyncUri(selectedVaultName)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Accessible File: ${currentUri?.lastPathSegment ?: "Configured external path"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                authManager.isPickingFile = true
                                autoSyncCreateLauncher.launch("${selectedVaultName}_accessible.ofs")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Change Accessible File Location")
                        }
                    }
                }
            }

            // Backup & Migration Options (PC / Share / Reinstallation)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup & Device Transfer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Export the single-file encrypted backup container ($selectedVaultName.ofs) to share to PC or transfer to a new device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            authManager.isPickingFile = true
                            exportBackupLauncher.launch("${selectedVaultName}_backup.ofs")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Backup Container ($selectedVaultName.ofs)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            authManager.isPickingFile = true
                            restoreBackupLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore Container from File")
                    }
                }
            }

            // Security Options (Dual Lock: App Lock + Vault Master Lock)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Security Preferences",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Biometric Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Biometric Unlock", style = MaterialTheme.typography.bodyLarge)
                                Text("Unlock vault with fingerprint or Face ID", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Switch(
                            checked = isBiometricEnabled.value,
                            onCheckedChange = { enabled ->
                                isBiometricEnabled.value = enabled
                                authManager.setBiometricEnabled(enabled)
                                if (enabled) {
                                    authManager.enableBiometric()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Change App Lock PIN
                    Button(
                        onClick = { showChangeAppLockDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LockReset, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change App Lock PIN (UI)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Change Vault Master Password
                    OutlinedButton(
                        onClick = { showChangeVaultMasterDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LockReset, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Vault Master Password ($selectedVaultName)")
                    }
                }
            }

            // Lock Vault Button
            Button(
                onClick = onLockRequested,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Lock Vault Now",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Overwrite Hash Mismatch Warning Dialog
    if (showOverwriteWarningDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteWarningDialog = false },
            title = { Text("Hash Mismatch Warning") },
            text = {
                Text(
                    "The file at the accessible storage path differs from your current internal vault container (SHA-256 hash mismatch).\n\n" +
                    "Select 'Overwrite' to replace the external file with current internal vault state, or 'Create New File' to select a new destination path."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverwriteWarningDialog = false
                        authManager.setAutoSyncEnabled(selectedVaultName, true)
                        authManager.syncAccessibleStorageNow(selectedVaultName)
                        isAutoSyncChecked = true
                        statusMessage = "External file overwritten and Auto-Sync enabled!"
                    }
                ) {
                    Text("Overwrite External File")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOverwriteWarningDialog = false
                        authManager.isPickingFile = true
                        autoSyncCreateLauncher.launch("${selectedVaultName}_accessible_${System.currentTimeMillis() % 10000}.ofs")
                    }
                ) {
                    Text("Create New File")
                }
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
                statusMessage = "Vault container restored and unlocked successfully!"
            }
        )
    }

    // Status Message Dialog
    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text("Storage Auto-Sync Status") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { statusMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    // Change App Lock PIN Dialog
    if (showChangeAppLockDialog) {
        var oldPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangeAppLockDialog = false },
            title = { Text("Change App Lock PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = oldPin,
                        onValueChange = { input ->
                            oldPin = input.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("Current App Lock PIN (4 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { input ->
                            newPin = input.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("New App Lock PIN (4 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (oldPin.length == 4 && newPin.length == 4) {
                            val success = authManager.changeAppLockPin(oldPin, newPin)
                            if (success) {
                                showChangeAppLockDialog = false
                            } else {
                                errorMessage = "Incorrect current App Lock PIN."
                            }
                        } else {
                            errorMessage = "PINs must be 4 digits."
                        }
                    }
                ) {
                    Text("Update App Lock PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeAppLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Change Vault Master Password Dialog
    if (showChangeVaultMasterDialog) {
        var oldPassword by remember { mutableStateOf("") }
        var newPassword by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangeVaultMasterDialog = false },
            title = { Text("Change Vault Master Password ($selectedVaultName)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("Current Vault Master Password") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New Vault Master Password") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm New Vault Password") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (oldPassword.isEmpty()) {
                            errorMessage = "Please enter current Vault Master Password."
                        } else if (newPassword.length < 4) {
                            errorMessage = "New password must be at least 4 characters."
                        } else if (newPassword != confirmPassword) {
                            errorMessage = "New passwords do not match."
                        } else {
                            val success = authManager.changeVaultMasterPassword(oldPassword, newPassword, selectedVaultName)
                            if (success) {
                                showChangeVaultMasterDialog = false
                            } else {
                                errorMessage = "Incorrect current Vault Master Password."
                            }
                        }
                    }
                ) {
                    Text("Update Vault Password")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeVaultMasterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val kb = bytes / 1000.0
    if (kb < 1000) return String.format(Locale.getDefault(), "%.2f KB", kb)
    val mb = kb / 1000.0
    if (mb < 1000) return String.format(Locale.getDefault(), "%.2f MB", mb)
    val gb = mb / 1000.0
    return String.format(Locale.getDefault(), "%.2f GB", gb)
}
