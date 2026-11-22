package com.openlock.filesystem.ui.auth

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.openlock.filesystem.auth.AuthManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AuthScreen(
    isSetupMode: Boolean,
    isBiometricAvailable: Boolean,
    getRemainingLockoutSeconds: () -> Long,
    getFailedAttemptsCount: () -> Int,
    onPinSubmitted: (String) -> Boolean,
    onBiometricRequested: () -> Unit,
    onSetupComplete: (String) -> Unit,
    title: String? = null,
    subtitle: String? = null
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirmingStep by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutSeconds by remember { mutableLongStateOf(getRemainingLockoutSeconds()) }
    var failedAttempts by remember { mutableIntStateOf(getFailedAttemptsCount()) }

    // Live Lockout Countdown Timer Loop
    LaunchedEffect(lockoutSeconds) {
        if (lockoutSeconds > 0) {
            delay(1000L.milliseconds)
            lockoutSeconds = getRemainingLockoutSeconds()
            if (lockoutSeconds == 0L) {
                errorMessage = null
            }
        }
    }

    // Scramble digit locations on each screen session or retry attempt
    var scrambledDigits by remember {
        mutableStateOf((0..9).map { it.toString() }.shuffled())
    }

    fun rescrambleDigits() {
        scrambledDigits = (0..9).map { it.toString() }.shuffled()
    }

    val isLockedOut = lockoutSeconds > 0

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Logo",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title ?: if (isSetupMode) {
                        if (isConfirmingStep) "Confirm App Lock PIN" else "Create App Lock PIN"
                    } else if (isLockedOut) "App Locked Out" else "Enter App Lock PIN",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = subtitle ?: if (isLockedOut) "Too many incorrect attempts. Please wait."
                    else if (isSetupMode) "This PIN protects the application lock screen."
                    else "Enter your 4-digit PIN to unlock the app UI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentLength = if (isConfirmingStep) confirmPin.length else pin.length
                    repeat(4) { index ->
                        val isFilled = index < currentLength
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isFilled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Emergency Wipe Warning when approaching limit (>= 9 failed attempts)
                if (!isSetupMode && failedAttempts >= 9 && 12 - failedAttempts > 0) {
                    Text(
                        text = "WARNING: ${12 - failedAttempts} attempt(s) remaining before total vault wipe!",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Lockout Banner / Error Message
                AnimatedVisibility(
                    visible = isLockedOut || errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = if (isLockedOut) formatLockoutDuration(lockoutSeconds) else (errorMessage ?: ""),
                        color = if (isLockedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Scrambled Numeric Keypad
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val gridDigits = listOf(
                    scrambledDigits.subList(0, 3),
                    scrambledDigits.subList(3, 6),
                    scrambledDigits.subList(6, 9)
                )

                gridDigits.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { digit ->
                            KeypadButton(
                                text = digit,
                                enabled = !isLockedOut
                            ) {
                                handleDigitInput(
                                    digit = digit,
                                    isSetupMode = isSetupMode,
                                    isConfirmingStep = isConfirmingStep,
                                    pin = pin,
                                    confirmPin = confirmPin,
                                    onPinChange = { pin = it },
                                    onConfirmPinChange = { confirmPin = it },
                                    onStepChange = { isConfirmingStep = it },
                                    onErrorChange = { errorMessage = it },
                                    onLockoutCheck = {
                                        lockoutSeconds = getRemainingLockoutSeconds()
                                        failedAttempts = getFailedAttemptsCount()
                                    },
                                    onPinSubmitted = onPinSubmitted,
                                    onSetupComplete = onSetupComplete,
                                    onRescramble = ::rescrambleDigits
                                )
                            }
                        }
                    }
                }

                // Bottom Row: Biometric, 10th Scrambled Digit, Backspace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isSetupMode && isBiometricAvailable) {
                        IconButton(
                            onClick = onBiometricRequested,
                            enabled = !isLockedOut,
                            modifier = Modifier.size(60.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = "Biometric Unlock",
                                modifier = Modifier.size(32.dp),
                                tint = if (!isLockedOut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(60.dp))
                    }

                    val lastDigit = scrambledDigits[9]
                    KeypadButton(
                        text = lastDigit,
                        enabled = !isLockedOut
                    ) {
                        handleDigitInput(
                            digit = lastDigit,
                            isSetupMode = isSetupMode,
                            isConfirmingStep = isConfirmingStep,
                            pin = pin,
                            confirmPin = confirmPin,
                            onPinChange = { pin = it },
                            onConfirmPinChange = { confirmPin = it },
                            onStepChange = { isConfirmingStep = it },
                            onErrorChange = { errorMessage = it },
                            onLockoutCheck = {
                                lockoutSeconds = getRemainingLockoutSeconds()
                                failedAttempts = getFailedAttemptsCount()
                            },
                            onPinSubmitted = onPinSubmitted,
                            onSetupComplete = onSetupComplete,
                            onRescramble = ::rescrambleDigits
                        )
                    }

                    IconButton(
                        onClick = {
                            if (!isLockedOut) {
                                errorMessage = null
                                if (isConfirmingStep) {
                                    if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                                } else {
                                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                }
                            }
                        },
                        enabled = !isLockedOut,
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Backspace",
                            modifier = Modifier.size(24.dp),
                            tint = if (!isLockedOut) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VaultUnlockScreen(
    getRemainingLockoutSeconds: () -> Long,
    getFailedAttemptsCount: () -> Int,
    onPasswordSubmitted: (String) -> Boolean,
    title: String? = null,
    subtitle: String? = null,
    availableVaults: List<String> = emptyList(),
    selectedVaultName: String? = null,
    onSelectVault: (String) -> Unit = {},
    onCreateVaultRequested: () -> Unit = {}
) {
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutSeconds by remember { mutableLongStateOf(getRemainingLockoutSeconds()) }
    var failedAttempts by remember { mutableIntStateOf(getFailedAttemptsCount()) }
    var showVaultMenu by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val isLockedOut = lockoutSeconds > 0

    // Autofocus password input field on composition
    LaunchedEffect(isLockedOut) {
        if (!isLockedOut) {
            delay(200L.milliseconds)
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus request safeguard
            }
        }
    }

    // Live Lockout Countdown Timer Loop
    LaunchedEffect(lockoutSeconds) {
        if (lockoutSeconds > 0) {
            delay(1000L.milliseconds)
            lockoutSeconds = getRemainingLockoutSeconds()
            if (lockoutSeconds == 0L) {
                errorMessage = null
            }
        }
    }

    val submitPassword = {
        if (password.isNotEmpty() && !isLockedOut) {
            errorMessage = null
            val success = onPasswordSubmitted(password)
            if (!success) {
                password = ""
                lockoutSeconds = getRemainingLockoutSeconds()
                failedAttempts = getFailedAttemptsCount()
                errorMessage = "Incorrect Vault Password."
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock Logo",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = title ?: if (isLockedOut) "Vault Locked Out" else "Unlock Encrypted Vault",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = subtitle ?: if (isLockedOut) "Too many incorrect attempts. Please wait."
                else "Enter your master password to unlock this encrypted file vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Vault File Selector / Switcher if existing vault files are present
            if (selectedVaultName != null && availableVaults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box {
                    OutlinedButton(onClick = { showVaultMenu = true }) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Vault File: $selectedVaultName.ofs")
                    }

                    DropdownMenu(
                        expanded = showVaultMenu,
                        onDismissRequest = { showVaultMenu = false }
                    ) {
                        availableVaults.forEach { vault ->
                            DropdownMenuItem(
                                text = { Text("$vault.ofs") },
                                onClick = {
                                    showVaultMenu = false
                                    password = ""
                                    onSelectVault(vault)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create New Vault File")
                                }
                            },
                            onClick = {
                                showVaultMenu = false
                                onCreateVaultRequested()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Vault Master Password") },
                singleLine = true,
                enabled = !isLockedOut,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submitPassword() }
                ),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = submitPassword,
                enabled = !isLockedOut && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unlock Vault")
            }

            // Emergency Wipe Warning when approaching limit (>= 9 failed attempts)
            if (failedAttempts >= 9 && 12 - failedAttempts > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "WARNING: ${12 - failedAttempts} attempt(s) remaining before total vault wipe!",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Lockout Banner / Error Message
            AnimatedVisibility(
                visible = isLockedOut || errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isLockedOut) formatLockoutDuration(lockoutSeconds) else (errorMessage ?: ""),
                    color = if (isLockedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CreateVaultDialog(
    authManager: AuthManager? = null,
    onDismiss: () -> Unit,
    onCreateVault: (name: String, password: String) -> Unit,
    onRestoreVaultRequested: (Uri) -> Unit = {}
) {
    var vaultName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // SAF Restore Backup File Picker
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            onRestoreVaultRequested(it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Encrypted Vault File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Specify a name and password for the new single-file encrypted vault container.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = vaultName,
                    onValueChange = { vaultName = it },
                    label = { Text("Vault File Name (e.g. PersonalVault)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Vault Master Password") },
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
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Vault Password") },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = {
                        authManager?.isPickingFile = true
                        restoreBackupLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore Old Vault File")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (vaultName.trim().isEmpty()) {
                        errorMessage = "Please enter a vault name."
                    } else if (password.isEmpty()) {
                        errorMessage = "Please enter a vault password."
                    } else if (password.length < 4) {
                        errorMessage = "Vault password must be at least 4 characters."
                    } else if (password != confirmPassword) {
                        errorMessage = "Passwords do not match."
                    } else {
                        onCreateVault(vaultName.trim(), password)
                    }
                }
            ) {
                Text("Create Vault File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun KeypadButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

private fun handleDigitInput(
    digit: String,
    isSetupMode: Boolean,
    isConfirmingStep: Boolean,
    pin: String,
    confirmPin: String,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onStepChange: (Boolean) -> Unit,
    onErrorChange: (String?) -> Unit,
    onLockoutCheck: () -> Unit,
    onPinSubmitted: (String) -> Boolean,
    onSetupComplete: (String) -> Unit,
    onRescramble: () -> Unit
) {
    onErrorChange(null)
    if (isSetupMode) {
        if (!isConfirmingStep) {
            val newPin = pin + digit
            if (newPin.length <= 4) onPinChange(newPin)
            if (newPin.length == 4) {
                onStepChange(true)
                onRescramble()
            }
        } else {
            val newConfirmPin = confirmPin + digit
            if (newConfirmPin.length <= 4) onConfirmPinChange(newConfirmPin)
            if (newConfirmPin.length == 4) {
                if (newConfirmPin == pin) {
                    onSetupComplete(pin)
                } else {
                    onErrorChange("PINs do not match. Try again.")
                    onPinChange("")
                    onConfirmPinChange("")
                    onStepChange(false)
                    onRescramble()
                }
            }
        }
    } else {
        val newPin = pin + digit
        if (newPin.length <= 4) onPinChange(newPin)
        if (newPin.length == 4) {
            val success = onPinSubmitted(newPin)
            if (!success) {
                onPinChange("")
                onLockoutCheck()
                onErrorChange("Incorrect PIN.")
                onRescramble()
            }
        }
    }
}

private fun formatLockoutDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "Try again in ${hours}h ${minutes}m"
        minutes > 0 -> "Try again in ${minutes}m ${seconds}s"
        else -> "Try again in ${seconds}s"
    }
}
