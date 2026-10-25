package com.ipphone.presentation.screens.filemanager

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.model.TransferDirection
import com.ipphone.domain.model.TransferFile
import com.ipphone.domain.model.TransferStatus
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.utils.FileNames
import com.ipphone.utils.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** The tabs of the in-app file browser (ShareIt style). */
enum class BrowserTab(val title: String) {
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    MUSIC("Music"),
    DOCS("Documents"),
    APPS("Apps")
}

/** One entry shown in the browser. [key] identifies it for selection. */
data class BrowserItem(
    val key: String,
    /** content:// URI, or an absolute path for installed apps. */
    val uri: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val dateMs: Long = 0L,
    val durationMs: Long = 0L,
    val icon: ImageBitmap? = null
)

data class SelectedFile(
    val uri: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val thumbnail: String? = null
)

data class FileManagerUiState(
    val targetUserId: String = "",
    val targetName: String = "",
    val tab: BrowserTab = BrowserTab.PHOTOS,
    val items: List<BrowserItem> = emptyList(),
    val itemsLoading: Boolean = false,
    val selectedFiles: List<SelectedFile> = emptyList(),
    val totalSelectedSize: Long = 0,
    val isLoading: Boolean = false
) {
    val selectedKeys: Set<String> get() = selectedFiles.mapTo(HashSet()) { it.uri }
}

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val fileTransferManager: FileTransferManager,
    private val fileTransferRepository: FileTransferRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val tabCache = HashMap<BrowserTab, List<BrowserItem>>()

    fun setTargetUser(userId: String) {
        if (_uiState.value.targetUserId == userId) return
        _uiState.update { it.copy(targetUserId = userId) }
        viewModelScope.launch {
            val name = try {
                userRepository.getUserByIdOnce(userId)?.deviceName.orEmpty()
            } catch (e: Exception) {
                ""
            }
            _uiState.update { it.copy(targetName = name) }
        }
        loadTab(BrowserTab.PHOTOS)
    }

    // ── Browsing ─────────────────────────────────────────────────────────────

    fun selectTab(tab: BrowserTab) {
        if (_uiState.value.tab == tab && _uiState.value.items.isNotEmpty()) return
        loadTab(tab)
    }

    /** Reload the current tab (e.g. after a permission was granted). */
    fun reload() {
        tabCache.remove(_uiState.value.tab)
        loadTab(_uiState.value.tab)
    }

    private fun loadTab(tab: BrowserTab) {
        val cached = tabCache[tab]
        if (cached != null) {
            _uiState.update { it.copy(tab = tab, items = cached, itemsLoading = false) }
            return
        }
        _uiState.update { it.copy(tab = tab, items = emptyList(), itemsLoading = true) }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    when (tab) {
                        BrowserTab.PHOTOS -> queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hasDuration = false)
                        BrowserTab.VIDEOS -> queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, hasDuration = true)
                        BrowserTab.MUSIC  -> queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, hasDuration = true)
                        BrowserTab.DOCS   -> queryDocuments()
                        BrowserTab.APPS   -> queryApps()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Browser query failed for $tab")
                    emptyList()
                }
            }
            tabCache[tab] = items
            _uiState.update { state ->
                if (state.tab == tab) state.copy(items = items, itemsLoading = false) else state
            }
        }
    }

    private fun queryMedia(collection: Uri, hasDuration: Boolean): List<BrowserItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (hasDuration) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()

        val result = ArrayList<BrowserItem>()
        appContext.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durCol  = if (hasDuration) c.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1

            while (c.moveToNext() && result.size < MAX_ITEMS) {
                val size = c.getLong(sizeCol)
                if (size <= 0L) continue
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                result += BrowserItem(
                    key        = uri,
                    uri        = uri,
                    name       = c.getString(nameCol) ?: "file",
                    size       = size,
                    mimeType   = c.getString(mimeCol),
                    dateMs     = c.getLong(dateCol) * 1000L,
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L
                )
            }
        }
        return result
    }

    private fun queryDocuments(): List<BrowserItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/%' " +
            "OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%') " +
            "AND ${MediaStore.Files.FileColumns.MIME_TYPE} != 'application/vnd.android.package-archive'"

        val result = ArrayList<BrowserItem>()
        appContext.contentResolver.query(
            collection, projection, selection, null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            while (c.moveToNext() && result.size < MAX_ITEMS) {
                val size = c.getLong(sizeCol)
                if (size <= 0L) continue
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                result += BrowserItem(
                    key      = uri,
                    uri      = uri,
                    name     = c.getString(nameCol) ?: "document",
                    size     = size,
                    mimeType = c.getString(mimeCol),
                    dateMs   = c.getLong(dateCol) * 1000L
                )
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun queryApps(): List<BrowserItem> {
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcher, 0)

        val result = ArrayList<BrowserItem>()
        val seen = HashSet<String>()
        for (ri in resolved) {
            val info = ri.activityInfo?.applicationInfo ?: continue
            if (info.packageName == appContext.packageName) continue
            if (!seen.add(info.packageName)) continue

            val apk = File(info.sourceDir)
            if (!apk.canRead()) continue

            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { info.packageName }
            val version = try { pm.getPackageInfo(info.packageName, 0).versionName } catch (e: Exception) { null }
            val fileName = FileNames.sanitizeFileName(
                "${label}${if (version.isNullOrBlank()) "" else "_$version"}.apk",
                "${info.packageName}.apk"
            )
            val icon = try {
                ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            result += BrowserItem(
                key      = "app:${info.packageName}",
                uri      = apk.absolutePath,
                name     = fileName,
                size     = apk.length(),
                mimeType = "application/vnd.android.package-archive",
                icon     = icon
            )
        }
        return result.sortedBy { it.name.lowercase() }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    fun toggleItem(item: BrowserItem) {
        val current = _uiState.value.selectedFiles
        val updated = if (current.any { it.uri == item.uri }) {
            current.filterNot { it.uri == item.uri }
        } else {
            current + SelectedFile(item.uri, item.name, item.size, item.mimeType)
        }
        publishSelection(updated)
    }

    /** Selects every item of the current tab, or clears them if all are already selected. */
    fun toggleSelectAll() {
        val state = _uiState.value
        val keys = state.selectedKeys
        val allSelected = state.items.isNotEmpty() && state.items.all { it.uri in keys }
        val updated = if (allSelected) {
            val visible = state.items.mapTo(HashSet()) { it.uri }
            state.selectedFiles.filterNot { it.uri in visible }
        } else {
            val extra = state.items
                .filter { it.uri !in keys }
                .map { SelectedFile(it.uri, it.name, it.size, it.mimeType) }
            state.selectedFiles + extra
        }
        publishSelection(updated)
    }

    /** Files picked with the system picker ("Browse other files"). */
    fun addFilesToSelection(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val newFiles = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = FileUtils.getFileName(context, uri) ?: return@mapNotNull null
                    SelectedFile(
                        uri = uri.toString(),
                        name = name,
                        size = FileUtils.getFileSize(context, uri),
                        mimeType = FileUtils.getMimeType(context, uri)
                    )
                }
            }
            publishSelection((_uiState.value.selectedFiles + newFiles).distinctBy { it.uri })
        }
    }

    fun removeFromSelection(file: SelectedFile) =
        publishSelection(_uiState.value.selectedFiles.filter { it.uri != file.uri })

    fun clearSelection() = publishSelection(emptyList())

    private fun publishSelection(files: List<SelectedFile>) {
        _uiState.update {
            it.copy(selectedFiles = files, totalSelectedSize = files.sumOf { f -> f.size })
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    fun startTransfer(): String {
        val state = _uiState.value
        if (state.isLoading || state.selectedFiles.isEmpty()) return ""

        _uiState.update { it.copy(isLoading = true) }
        val transferId = UUID.randomUUID().toString()

        viewModelScope.launch {
            try {
                val transferFiles = state.selectedFiles.mapIndexed { index, file ->
                    TransferFile(
                        id = UUID.randomUUID().toString(),
                        transferId = transferId,
                        fileName = file.name,
                        filePath = file.uri,
                        fileSize = file.size,
                        mimeType = file.mimeType,
                        indexOrder = index
                    )
                }

                val transfer = FileTransfer(
                    id = transferId,
                    peerId = state.targetUserId,
                    direction = TransferDirection.OUTGOING,
                    status = TransferStatus.PENDING,
                    totalSize = state.totalSelectedSize,
                    fileCount = state.selectedFiles.size,
                    files = transferFiles
                )

                fileTransferRepository.saveTransfer(transfer)
                fileTransferManager.startTransfer(transfer)
                publishSelection(emptyList())
            } catch (e: Exception) {
                Timber.e(e, "Failed to start transfer")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
        return transferId
    }

    private companion object {
        const val MAX_ITEMS = 5000
    }
}
