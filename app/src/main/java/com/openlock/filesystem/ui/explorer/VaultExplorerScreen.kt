package com.openlock.filesystem.ui.explorer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openlock.filesystem.auth.AuthManager
import com.openlock.filesystem.data.VaultItemEntity
import com.openlock.filesystem.data.VaultRepository
import com.openlock.filesystem.data.VaultTaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey
import kotlin.time.Duration.Companion.milliseconds

// Custom ActivityResultContract for selecting ANY document or media file (single or multiple).
// Sets Intent.ACTION_OPEN_DOCUMENT + CATEGORY_OPENABLE + type="*/*" + EXTRA_ALLOW_MULTIPLE=true.
// Guaranteed to show all files (documents, PDFs, ZIPs, media, downloads) across all Android devices.
class OpenAnyDocumentContract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return emptyList()
        }
        val uris = mutableListOf<Uri>()
        intent.data?.let { uris.add(it) }
        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                if (uri != null && !uris.contains(uri)) {
                    uris.add(uri)
                }
            }
        }
        return uris
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultExplorerScreen(
    vaultRepository: VaultRepository,
    masterKey: SecretKey,
    currentFolderId: String?,
    folderPath: List<Pair<String?, String>>,
    onFolderSelected: (String?, String) -> Unit,
    onNavigateBack: () -> Unit,
    onFilePreviewRequested: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    authManager: AuthManager? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItemIds by remember { mutableStateOf(setOf<String>()) }
    var itemsToExportToDir by remember { mutableStateOf<List<String>?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgressText by remember { mutableStateOf("Exporting items...") }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // System Back Press Handler: Step back to parent folder if inside a subfolder, cancel search, or exit selection mode
    BackHandler(enabled = isSearchActive || isSelectionMode || folderPath.size > 1) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedItemIds = emptySet()
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (folderPath.size > 1) {
            onNavigateBack()
        }
    }

    // Re-collect items Flow whenever currentFolderId or searchQuery changes
    val itemsState = remember(currentFolderId, searchQuery) {
        if (searchQuery.isBlank()) {
            vaultRepository.getItemsInFolder(currentFolderId)
        } else {
            vaultRepository.searchItems(searchQuery)
        }
    }.collectAsState(initial = emptyList())

    val allItemsState = vaultRepository.getAllItems().collectAsState(initial = emptyList())

    LaunchedEffect(itemsState.value, allItemsState.value) {
        Log.d("VaultUI", "VaultUI Re-rendered. Items in current folder '$currentFolderId': ${itemsState.value.size}, Total items in DB: ${allItemsState.value.size}")
    }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var selectedExportItem by remember { mutableStateOf<VaultItemEntity?>(null) }
    var itemToRename by remember { mutableStateOf<VaultItemEntity?>(null) }
    var itemToDelete by remember { mutableStateOf<VaultItemEntity?>(null) }
    var pendingShredConfirmationUris by remember { mutableStateOf<List<Uri>?>(null) }

    // Required Storage & Media permissions for current Android SDK version
    val requiredPermissions = remember { com.openlock.filesystem.crypto.SecurityUtils.getRequiredManifestPermissions() }

    fun executeFileImport(uris: List<Uri>, shredSource: Boolean) {
        if (uris.isEmpty()) {
            Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "Selected ${uris.size} file(s). Starting import...", Toast.LENGTH_SHORT).show()
        isImporting = true

        VaultTaskService.start(context, "Importing Files", "Encrypting and importing payload...")

        // Execute import on IO scope so it is NOT canceled when Activity resumes from File Picker
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            val errors = mutableListOf<String>()

            try {
                if (uris.size > 1) {
                    val (succ, fail) = vaultRepository.importFiles(uris, currentFolderId, masterKey, shredSource = shredSource)
                    successCount = succ
                    if (fail > 0) {
                        errors.add("$fail file(s) failed during batch import.")
                    }
                } else {
                    for (uri in uris) {
                        try {
                            Log.d("VaultImport", "Starting import for URI: $uri")
                            val importedItem = vaultRepository.importFile(uri, currentFolderId, masterKey, shredSource = shredSource)
                            successCount++
                            VaultTaskService.update(context, "Importing Files", "Encrypting file #$successCount: ${importedItem.name}")
                            Log.d("VaultImport", "Successfully imported file: ${importedItem.name} (id: ${importedItem.id})")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Successfully imported '${importedItem.name}' (${formatFileSize(importedItem.sizeBytes)})" +
                                            if (shredSource) " (source shredded)" else "",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            val errorMsg = "Failed to import ${uri.lastPathSegment ?: "file"}: ${e.localizedMessage ?: e.toString()}"
                            errors.add(errorMsg)
                            Log.e("VaultImport", "File import exception for URI $uri", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                VaultTaskService.stop(context, "File Import Complete", "Imported $successCount file(s)")
            } catch (e: Exception) {
                VaultTaskService.stop(context, "File Import Failed", "Error: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                isImporting = false
                val shredText = if (shredSource) " & shredded source" else ""
                if (errors.isEmpty()) {
                    snackbarHostState.showSnackbar("Successfully imported$shredText $successCount file(s)")
                } else {
                    snackbarHostState.showSnackbar(
                        "Import finished: $successCount succeeded, ${errors.size} failed. Check Toast for details."
                    )
                }
            }
        }
    }

    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val shredEnabled = authManager?.isShredAfterImportEnabled() ?: false
        val alreadyPrompted = authManager?.hasPromptedShredWarningInSession ?: false

        if (shredEnabled && !alreadyPrompted) {
            pendingShredConfirmationUris = uris
        } else {
            executeFileImport(uris, shredSource = shredEnabled)
        }
    }

    var importProgressText by remember { mutableStateOf("Encrypting & Importing... Please wait.") }

    fun processSelectedFolderUri(treeUri: Uri) {
        Toast.makeText(context, "Starting folder import...", Toast.LENGTH_SHORT).show()
        isImporting = true
        importProgressText = "Parsing folder structure & encrypting files..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("VaultImport", "Starting folder import for URI: $treeUri")
                val result = vaultRepository.importFolder(
                    treeUri = treeUri,
                    targetParentFolderId = currentFolderId,
                    masterKey = masterKey,
                    onProgressUpdate = { fileName, count ->
                        importProgressText = "Encrypting file #${count + 1}: $fileName"
                    }
                )
                withContext(Dispatchers.Main) {
                    isImporting = false
                    val msg = "Imported folder '${result.rootFolderName}' (${result.filesImported} files, ${result.foldersCreated} folders)"
                    Log.d("VaultImport", msg)
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    snackbarHostState.showSnackbar(msg)
                }
            } catch (e: Exception) {
                Log.e("VaultImport", "Folder import exception for URI $treeUri", e)
                withContext(Dispatchers.Main) {
                    isImporting = false
                    val errorMsg = "Folder import failed: ${e.localizedMessage ?: e.toString()}"
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    snackbarHostState.showSnackbar(errorMsg)
                }
            }
        }
    }

    // Unified Document Picker Launcher using ACTION_OPEN_DOCUMENT + CATEGORY_OPENABLE
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = OpenAnyDocumentContract()
    ) { uris: List<Uri> ->
        Log.d("VaultImport", "OpenAnyDocumentContract returned ${uris.size} URI(s): $uris")
        processSelectedUris(uris)
    }

    // SAF Document Tree Picker Launcher for Folder Import
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        Log.d("VaultImport", "OpenDocumentTree returned URI: $treeUri")
        treeUri?.let { processSelectedFolderUri(it) }
    }

    var pendingImportOnPermission by remember { mutableStateOf(false) }
    var pendingFolderImportOnPermission by remember { mutableStateOf(false) }

    // Permission Launcher for Storage/Media Access
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val hasAnyGranted = permissionsMap.values.any { it }
        if (pendingImportOnPermission) {
            pendingImportOnPermission = false
            if (hasAnyGranted) {
                filePickerLauncher.launch(Unit)
            } else {
                Toast.makeText(context, "Storage/Media permission denied", Toast.LENGTH_LONG).show()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Storage/Media permission is required to select files")
                }
            }
        } else if (pendingFolderImportOnPermission) {
            pendingFolderImportOnPermission = false
            if (hasAnyGranted) {
                folderPickerLauncher.launch(null)
            } else {
                Toast.makeText(context, "Storage/Media permission denied", Toast.LENGTH_LONG).show()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Storage/Media permission is required to select folders")
                }
            }
        }
    }

    fun launchFileImportPicker() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        authManager?.isPickingFile = true
        if (allGranted) {
            filePickerLauncher.launch(Unit)
        } else {
            pendingImportOnPermission = true
            permissionLauncher.launch(requiredPermissions)
        }
    }

    fun launchFolderImportPicker() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        authManager?.isPickingFile = true
        if (allGranted) {
            folderPickerLauncher.launch(null)
        } else {
            pendingFolderImportOnPermission = true
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Automatically check and request missing storage/media/notification permissions on first launch
    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // SAF Export Launcher
    val fileExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            selectedExportItem?.let { item ->
                coroutineScope.launch {
                    try {
                        vaultRepository.exportFile(item.id, destUri, masterKey)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Exported '${item.name}' successfully", Toast.LENGTH_SHORT).show()
                        }
                        snackbarHostState.showSnackbar("Exported '${item.name}' successfully")
                    } catch (e: Exception) {
                        val errorMsg = "Export failed: ${e.localizedMessage ?: e.toString()}"
                        Log.e("VaultExplorer", "Export failed", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                        }
                        snackbarHostState.showSnackbar(errorMsg)
                    }
                }
            }
        }
    }

    // SAF Directory Export Launcher for Folder & Multi-Select Exports
    val directoryExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { destUri ->
            val targetIds = itemsToExportToDir
            if (!targetIds.isNullOrEmpty()) {
                isExporting = true
                exportProgressText = "Exporting ${targetIds.size} item(s)... Please wait."
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val (succ, fail) = vaultRepository.exportItemsToDirectory(
                            itemIds = targetIds,
                            targetDirUri = destUri,
                            masterKey = masterKey,
                            onProgress = { itemName ->
                                exportProgressText = "Decrypting & exporting: $itemName"
                            }
                        )
                        withContext(Dispatchers.Main) {
                            isExporting = false
                            itemsToExportToDir = null
                            isSelectionMode = false
                            selectedItemIds = emptySet()
                            val msg = "Export complete: $succ file(s) exported" + if (fail > 0) ", $fail failed" else ""
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            snackbarHostState.showSnackbar(msg)
                        }
                    } catch (e: Exception) {
                        Log.e("VaultExplorer", "Folder/Batch export failed", e)
                        withContext(Dispatchers.Main) {
                            isExporting = false
                            itemsToExportToDir = null
                            val errorMsg = "Export failed: ${e.localizedMessage ?: e.toString()}"
                            Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            snackbarHostState.showSnackbar(errorMsg)
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(text = "${selectedItemIds.size} selected")
                    } else if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search files...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        Text(
                            text = folderPath.lastOrNull()?.second ?: "Open FS",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedItemIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    } else if (currentFolderId != null || isSearchActive) {
                        IconButton(onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                searchQuery = ""
                            } else {
                                onNavigateBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val currentFolderItems = itemsState.value
                        IconButton(onClick = {
                            selectedItemIds = if (selectedItemIds.size == currentFolderItems.size) {
                                emptySet()
                            } else {
                                currentFolderItems.map { it.id }.toSet()
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(
                            onClick = {
                                if (selectedItemIds.isNotEmpty()) {
                                    itemsToExportToDir = selectedItemIds.toList()
                                    authManager?.isPickingFile = true
                                    directoryExportLauncher.launch(null)
                                }
                            },
                            enabled = selectedItemIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Export Selected")
                        }
                        IconButton(
                            onClick = {
                                if (selectedItemIds.isNotEmpty()) {
                                    showBatchDeleteDialog = true
                                }
                            },
                            enabled = selectedItemIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                        }
                    } else {
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Select Mode")
                        }
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                var fabMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    FloatingActionButton(onClick = { fabMenuExpanded = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                    DropdownMenu(
                        expanded = fabMenuExpanded,
                        onDismissRequest = { fabMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import File(s)") },
                            onClick = {
                                fabMenuExpanded = false
                                launchFileImportPicker()
                            },
                            leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import Folder") },
                            onClick = {
                                fabMenuExpanded = false
                                launchFolderImportPicker()
                            },
                            leadingIcon = { Icon(Icons.Default.DriveFolderUpload, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("New Folder") },
                            onClick = {
                                fabMenuExpanded = false
                                showCreateFolderDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Importing Progress Banner
            if (isImporting) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = importProgressText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Exporting Progress Banner
            if (isExporting) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = exportProgressText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Breadcrumbs Bar
            if (folderPath.size > 1 && !isSearchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    folderPath.forEachIndexed { index, pair ->
                        Text(
                            text = pair.second,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == folderPath.lastIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == folderPath.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                if (index < folderPath.lastIndex) {
                                    onFolderSelected(pair.first, pair.second)
                                }
                            }
                        )
                        if (index < folderPath.lastIndex) {
                            Text(
                                text = " / ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Pull-to-Refresh Box (Drag top-to-bottom refresh)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        vaultRepository.containerManager.syncContainerState()
                        delay(600.milliseconds)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                val rawItems = itemsState.value
                val isRoot = currentFolderId.isNullOrEmpty() && searchQuery.isBlank()
                val items = if (rawItems.isEmpty() && isRoot) allItemsState.value else rawItems

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No files match your search"
                            else "This folder is empty.\nTap '+' to import encrypted files or drag down to refresh.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items, key = { it.id }) { item ->
                            val isSelected = selectedItemIds.contains(item.id)
                            VaultItemRow(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedItemIds = if (isSelected) {
                                            selectedItemIds - item.id
                                        } else {
                                            selectedItemIds + item.id
                                        }
                                    } else if (item.isFolder) {
                                        onFolderSelected(item.id, item.name)
                                    } else {
                                        onFilePreviewRequested(item.id)
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                    }
                                    selectedItemIds = if (isSelected) {
                                        selectedItemIds - item.id
                                    } else {
                                        selectedItemIds + item.id
                                    }
                                },
                                onPreview = { onFilePreviewRequested(item.id) },
                                onExport = {
                                    authManager?.isPickingFile = true
                                    selectedExportItem = item
                                    fileExportLauncher.launch(item.name)
                                },
                                onExportFolder = {
                                    itemsToExportToDir = listOf(item.id)
                                    authManager?.isPickingFile = true
                                    directoryExportLauncher.launch(null)
                                },
                                onRename = { itemToRename = item },
                                onDelete = { itemToDelete = item }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            val folderName = newFolderName.trim()
                            showCreateFolderDialog = false
                            coroutineScope.launch {
                                vaultRepository.createFolder(folderName, currentFolderId)
                            }
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Dialog
    itemToRename?.let { item ->
        var updatedName by remember { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("Rename Item") },
            text = {
                OutlinedTextField(
                    value = updatedName,
                    onValueChange = { updatedName = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (updatedName.isNotBlank()) {
                            val newName = updatedName.trim()
                            itemToRename = null
                            coroutineScope.launch {
                                vaultRepository.renameItem(item.id, newName)
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete ${if (item.isFolder) "Folder" else "File"}") },
            text = { Text("Are you sure you want to permanently delete '${item.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idToDelete = item.id
                        itemToDelete = null
                        coroutineScope.launch {
                            vaultRepository.deleteItem(idToDelete)
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Batch Delete Dialog
    if (showBatchDeleteDialog && selectedItemIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete ${selectedItemIds.size} Item(s)") },
            text = { Text("Are you sure you want to permanently delete the selected ${selectedItemIds.size} item(s)?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedItemIds.toList()
                        showBatchDeleteDialog = false
                        isSelectionMode = false
                        selectedItemIds = emptySet()
                        coroutineScope.launch {
                            for (id in idsToDelete) {
                                vaultRepository.deleteItem(id)
                            }
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Shred Source File Confirmation Dialog
    pendingShredConfirmationUris?.let { uris ->
        AlertDialog(
            onDismissRequest = { pendingShredConfirmationUris = null },
            title = { Text("Confirm Source File Shredding") },
            text = {
                Text(
                    "The 'Shred Source File After Import' setting is currently enabled.\n\n" +
                    "After successful import into the encrypted vault, the original source file(s) on your device storage will be permanently shredded and deleted.\n\n" +
                    "Do you want to proceed with shredding original files?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingShredConfirmationUris = null
                        authManager?.setShredAfterImportEnabled(true)
                        authManager?.hasPromptedShredWarningInSession = true
                        executeFileImport(uris, shredSource = true)
                    }
                ) {
                    Text("Yes, Shred Original", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingShredConfirmationUris = null
                        authManager?.setShredAfterImportEnabled(false)
                        authManager?.hasPromptedShredWarningInSession = true
                        executeFileImport(uris, shredSource = false)
                    }
                ) {
                    Text("No, Keep Original & Disable Shred")
                }
            }
        )
    }
}

@Composable
private fun VaultItemRow(
    item: VaultItemEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onExportFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick() }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() }
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = if (item.isFolder) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (item.isFolder) "Folder"
                else "${formatFileSize(item.sizeBytes)} • ${formatDate(item.createdAtEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!isSelectionMode) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (item.isFolder) {
                        DropdownMenuItem(
                            text = { Text("Export Folder") },
                            onClick = {
                                menuExpanded = false
                                onExportFolder()
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Preview") },
                            onClick = {
                                menuExpanded = false
                                onPreview()
                            },
                            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export & Decrypt") },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                }
            }
        }
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

private fun formatDate(epochMs: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(epochMs))
}
