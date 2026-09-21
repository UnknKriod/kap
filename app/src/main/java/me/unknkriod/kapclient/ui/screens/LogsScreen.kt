package me.unknkriod.kapclient.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.unknkriod.kapclient.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logLines by remember { mutableStateOf<List<AnnotatedString>>(emptyList()) }
    var rawLogsText by remember { mutableStateOf("") }
    var isSharing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    var selectedDate by remember { mutableStateOf<String?>(null) }
    var availableDates by remember { mutableStateOf(emptyList<String>()) }
    val logsDir = remember { File(context.filesDir, "archived_logs").apply { if (!exists()) mkdirs() } }

    val isLogsLoaded = logLines.isNotEmpty()

    fun formatLine(line: String): AnnotatedString {
        val color = when {
            line.contains(" E ") || line.contains(" E/") -> Color(0xFFF44336) // Error
            line.contains(" W ") || line.contains(" W/") -> Color(0xFFFFC107) // Warning
            line.contains(" I ") || line.contains(" I/") -> Color(0xFF4CAF50) // Info
            line.contains(" D ") || line.contains(" D/") -> Color(0xFF2196F3) // Debug
            line.contains(" V ") || line.contains(" V/") -> Color(0xFF9E9E9E) // Verbose
            else -> null
        }
        return buildAnnotatedString {
            if (color != null) {
                pushStyle(SpanStyle(color = color))
                append(line)
                pop()
            } else {
                append(line)
            }
        }
    }

    fun fetchLogs() {
        if (isLoading) return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            var process: Process? = null
            var reader: BufferedReader? = null
            try {
                val cmd = "logcat -v threadtime -d -t 2000 View:S VRI:S InsetsController:S InputTransport:S ImeFocusController:S ViewRootImpl:S *:I"
                process = Runtime.getRuntime().exec(cmd)
                reader = BufferedReader(InputStreamReader(process.inputStream))
                val lines = mutableListOf<AnnotatedString>()
                val rawText = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    rawText.append(line).append("\n")
                    lines.add(formatLine(line!!))
                }
                withContext(Dispatchers.Main) {
                    logLines = lines
                    rawLogsText = rawText.toString()
                    isLoading = false
                    if (lines.isNotEmpty()) {
                        listState.scrollToItem(lines.size - 1)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logLines = listOf(AnnotatedString("Error fetching logs: ${e.message}"))
                    isLoading = false
                }
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                try { process?.destroy() } catch (_: Exception) {}
            }
        }
    }

    fun fetchArchivedLogs(date: String) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val logFile = File(logsDir, "logs_$date.txt")
                if (logFile.exists()) {
                    val text = logFile.readText()
                    val lines = text.lines().filter { it.isNotBlank() }.map { formatLine(it) }
                    withContext(Dispatchers.Main) {
                        logLines = lines
                        rawLogsText = text
                        isLoading = false
                        if (lines.isNotEmpty()) {
                            listState.scrollToItem(lines.size - 1)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        logLines = listOf(AnnotatedString("Log file for $date not found"))
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logLines = listOf(AnnotatedString("Error reading logs for $date: ${e.message}"))
                    isLoading = false
                }
            }
        }
    }

    fun archiveLogs() {
        scope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec("logcat -v threadtime -d -t 5000")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val logText = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logText.append(line).append("\n")
                }
                
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val logFile = File(logsDir, "logs_$today.txt")
                logFile.writeText(logText.toString())
                
                val allFiles = logsDir.listFiles()?.filter { it.name.startsWith("logs_") && it.name.endsWith(".txt") } ?: emptyList()
                if (allFiles.size > 7) {
                    allFiles.sortedBy { it.name }.take(allFiles.size - 7).forEach { it.delete() }
                }

                val dates = logsDir.listFiles()
                    ?.filter { it.name.startsWith("logs_") && it.name.endsWith(".txt") }
                    ?.map { it.name.removePrefix("logs_").removeSuffix(".txt") }
                    ?.sortedDescending() ?: emptyList()
                
                withContext(Dispatchers.Main) {
                    availableDates = dates
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(rawLogsText.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun shareLogsFile() {
        if (!isLogsLoaded || isSharing) return
        isSharing = true
        scope.launch(Dispatchers.IO) {
            try {
                val cachePath = File(context.cacheDir, "logs")
                if (!cachePath.exists()) cachePath.mkdirs()
                val logFileName = if (selectedDate != null) "logs_$selectedDate.txt" else "kap_client_logs.txt"
                val logFile = File(cachePath, logFileName)
                logFile.writeText(rawLogsText)

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile
                )

                val shareIntent = ShareCompat.IntentBuilder(context)
                    .setType("text/plain")
                    .setStream(contentUri)
                    .setChooserTitle(R.string.export)
                    .intent
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply {
                        clipData = ClipData.newRawUri(null, contentUri)
                    }

                val chooser = Intent.createChooser(shareIntent, context.getString(R.string.export))
                context.startActivity(chooser)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    isSharing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        archiveLogs()
    }

    LaunchedEffect(selectedDate) {
        if (selectedDate == null) {
            fetchLogs()
        } else {
            fetchArchivedLogs(selectedDate!!)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(
                                text = selectedDate ?: stringResource(R.string.logs),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs) + " (Live)") },
                                onClick = {
                                    selectedDate = null
                                    expanded = false
                                }
                            )
                            availableDates.forEach { date ->
                                DropdownMenuItem(
                                    text = { Text(date) },
                                    onClick = {
                                        selectedDate = date
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (selectedDate == null) {
                        IconButton(onClick = { fetchLogs() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                    IconButton(
                        onClick = { 
                            val fileName = if (selectedDate != null) "logs_$selectedDate.txt" else "kap_client_logs.txt"
                            createDocumentLauncher.launch(fileName) 
                        },
                        enabled = isLogsLoaded && !isSharing
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                    }
                    IconButton(
                        onClick = { shareLogsFile() },
                        enabled = isLogsLoaded && !isSharing
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212))) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    state = listState
                ) {
                    items(logLines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }

            if (isLogsLoaded && listState.firstVisibleItemIndex < logLines.size - 20) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(logLines.size - 1)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                }
            }

            if (isSharing) {
                Dialog(onDismissRequest = {}) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.export))
                        }
                    }
                }
            }
        }
    }
}
