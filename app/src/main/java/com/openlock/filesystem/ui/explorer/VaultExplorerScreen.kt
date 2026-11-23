package com.openlock.filesystem.ui.explorer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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

    // System Back Press Handler: Step back to parent folder if inside a subfolder or cancel search
    BackHandler(enabled = isSearchActive || folderPath.size > 1) {
        if (isSearchActive) {
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

    // Required Storage & Media permissions for current Android SDK version
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "Selected ${uris.size} file(s). Starting import...", Toast.LENGTH_SHORT).show()
        isImporting = true

        // Execute import on IO scope so it is NOT cancelled when Activity resumes from File Picker
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            val errors = mutableListOf<String>()

            if (uris.size > 1) {
                val (succ, fail) = vaultRepository.importFiles(uris, currentFolderId, masterKey)
                successCount = succ
                if (fail > 0) {
                    errors.add("$fail file(s) failed during batch import.")
                }
            } else {
                for (uri in uris) {
                    try {
                        Log.d("VaultImport", "Starting import for URI: $uri")
                        val importedItem = vaultRepository.importFile(uri, currentFolderId, masterKey)
                        successCount++
                        Log.d("VaultImport", "Successfully imported file: ${importedItem.name} (id: ${importedItem.id})")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Successfully imported '${importedItem.name}' (${formatFileSize(importedItem.sizeBytes)})",
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

            withContext(Dispatchers.Main) {
                isImporting = false
                if (errors.isEmpty()) {
                    snackbarHostState.showSnackbar("Successfully imported $successCount file(s)")
                } else {
                    snackbarHostState.showSnackbar(
                        "Import finished: $successCount succeeded, ${errors.size} failed. Check Toast for details."
                    )
                }
            }
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
        if (allGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        if (allGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            folderPickerLauncher.launch(null)
        } else {
            pendingFolderImportOnPermission = true
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Automatically check and request missing storage/media permissions on first launch
    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
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
                    if (currentFolderId != null || isSearchActive) {
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
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
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
                            VaultItemRow(
                                item = item,
                                onClick = {
                                    if (item.isFolder) {
                                        onFolderSelected(item.id, item.name)
                                    } else {
                                        onFilePreviewRequested(item.id)
                                    }
                                },
                                onPreview = { onFilePreviewRequested(item.id) },
                                onExport = {
                                    authManager?.isPickingFile = true
                                    selectedExportItem = item
                                    fileExportLauncher.launch(item.name)
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
}

@Composable
private fun VaultItemRow(
    item: VaultItemEntity,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (!item.isFolder) {
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
