package com.inkpress.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Simple data class to represent a converted article
    data class ConversionItem(
        val title: String,
        val url: String,
        val timestamp: Long,
        val filePath: String
    ) {
        fun serialize(): String {
            return "${escape(title)}||${escape(url)}||$timestamp||${escape(filePath)}"
        }

        companion object {
            private fun escape(s: String) = s.replace("|", "\\|")
            private fun unescape(s: String) = s.replace("\\|", "|")

            fun deserialize(s: String): ConversionItem? {
                val parts = s.split("(?<!\\\\)\\|\\|".toRegex())
                if (parts.size >= 4) {
                    return ConversionItem(
                        title = unescape(parts[0]),
                        url = unescape(parts[1]),
                        timestamp = parts[2].toLongOrNull() ?: 0L,
                        filePath = unescape(parts[3])
                    )
                }
                return null
            }
        }
    }

    private val conversionList = mutableStateListOf<ConversionItem>()
    private var isConverting = mutableStateOf(false)
    private var statusMessage = mutableStateOf("")
    private val uploadStatuses = mutableStateMapOf<String, String>()

    private fun deleteItem(item: ConversionItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = item.filePath.substringAfterLast("/")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, "InkPress/$fileName")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Ignore file deletion errors
            }
            withContext(Dispatchers.Main) {
                conversionList.remove(item)
                uploadStatuses.remove(item.filePath)
                saveHistory()
                Toast.makeText(this@MainActivity, "Deleted from history and storage", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pushToX3(item: ConversionItem) {
        val sharedPrefs = getSharedPreferences("inkpress_settings", Context.MODE_PRIVATE)
        val host = sharedPrefs.getString("x3_host", "192.168.86.125") ?: "192.168.86.125"
        val portStr = sharedPrefs.getString("x3_port", "80") ?: "80"
        val uploadPath = sharedPrefs.getString("x3_path", "/upload") ?: "/upload"
        val folder = sharedPrefs.getString("x3_folder", "InkPress") ?: "InkPress"
        val port = portStr.toIntOrNull() ?: 80

        uploadStatuses[item.filePath] = "Uploading..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                Uploader.pushFile(item.filePath, host, port, uploadPath, folder)
            }
            result.fold(
                onSuccess = {
                    uploadStatuses[item.filePath] = "Success!"
                },
                onFailure = { error ->
                    uploadStatuses[item.filePath] = "Failed: ${error.localizedMessage}"
                }
            )
        }
    }

    private fun pushAllToX3() {
        val items = conversionList.toList()
        if (items.isEmpty()) {
            Toast.makeText(this, "No files to push", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Pushing all ${items.size} files...", Toast.LENGTH_SHORT).show()
        items.forEach { pushToX3(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        loadSavedHistory()
        
        setContent {
            InkPressApp()
        }

        // Handle the incoming share intent if any
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    val url = extractUrl(sharedText)
                    if (url != null) {
                        startConversion(url)
                    } else {
                        Toast.makeText(this, "Could not find a valid URL to convert", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("""https?://[^\s]+""")
        return regex.find(text)?.value
    }

    private fun startConversion(url: String) {
        if (isConverting.value) return
        
        isConverting.value = true
        statusMessage.value = "Fetching article..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val article = Scraper.fetchAndClean(url)
                    
                    withContext(Dispatchers.Main) {
                        statusMessage.value = "Creating EPUB: ${article.title}..."
                    }

                    // Standardize file name: YYYY-MM-DD-title-inkpress format for chronological sorting
                    val datePrefix = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val slugTitle = article.title.take(40).trim()
                        .lowercase(Locale.US)
                        .replace(Regex("[^a-z0-9\\s-]"), "")
                        .replace(Regex("\\s+"), "-")
                        .replace(Regex("-+"), "-")
                        .trim('-')
                    val fileName = "$datePrefix-$slugTitle-inkpress"
                    
                    val outputPair = StorageHelper.getOutputStreamForEpub(this@MainActivity, fileName)
                        ?: throw Exception("Failed to create file output stream")

                    val outputStream = outputPair.first
                    val targetPath = outputPair.second

                    EpubBuilder.build(article.title, article.contentHtml, outputStream)

                    ConversionItem(
                        title = article.title,
                        url = article.sourceUrl,
                        timestamp = System.currentTimeMillis(),
                        filePath = targetPath
                    )
                }
            }

            isConverting.value = false
            result.fold(
                onSuccess = { item ->
                    conversionList.add(0, item)
                    saveHistory()
                    Toast.makeText(this@MainActivity, "Saved: ${item.title}", Toast.LENGTH_LONG).show()
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Failed: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun saveHistory() {
        val sharedPrefs = getSharedPreferences("inkpress_history", Context.MODE_PRIVATE)
        val serializedList = conversionList.map { it.serialize() }.toSet()
        sharedPrefs.edit().putStringSet("history_items", serializedList).apply()
    }

    private fun loadSavedHistory() {
        val sharedPrefs = getSharedPreferences("inkpress_history", Context.MODE_PRIVATE)
        val stringSet = sharedPrefs.getStringSet("history_items", emptySet()) ?: emptySet()
        val items = stringSet.mapNotNull { ConversionItem.deserialize(it) }
            .sortedByDescending { it.timestamp }
        conversionList.clear()
        conversionList.addAll(items)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun InkPressApp() {
        var inputUrl by remember { mutableStateOf("") }
        var showSettingsDialog by remember { mutableStateOf(false) }

        // Core theme colors (Sleek e-ink styled paper palette)
        val backgroundColor = Color(0xFFF9F9FB)
        val cardColor = Color(0xFFFFFFFF)
        val textColor = Color(0xFF1E2022)
        val primaryColor = Color(0xFF2C3E50) // Slate Blue/Charcoal
        val secondaryTextColor = Color(0xFF7F8C8D)

        // Settings Dialog Modal
        if (showSettingsDialog) {
            val sharedPrefs = remember { getSharedPreferences("inkpress_settings", Context.MODE_PRIVATE) }
            var host by remember { mutableStateOf(sharedPrefs.getString("x3_host", "192.168.86.125") ?: "192.168.86.125") }
            var port by remember { mutableStateOf(sharedPrefs.getString("x3_port", "80") ?: "80") }
            var path by remember { mutableStateOf(sharedPrefs.getString("x3_path", "/upload") ?: "/upload") }
            var folder by remember { mutableStateOf(sharedPrefs.getString("x3_folder", "InkPress") ?: "InkPress") }

            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Xteink X3 Settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Configure connection parameters for wireless file pushes to your Xteink X3 e-reader.", fontSize = 12.sp, color = secondaryTextColor)
                        
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("IP / Host Address") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text("Upload API Path") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = folder,
                            onValueChange = { folder = it },
                            label = { Text("Target Folder Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Text("Connection Presets:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ElevatedButton(
                                onClick = {
                                    host = "192.168.86.125"
                                    port = "80"
                                    path = "/upload"
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Home Wi-Fi", fontSize = 11.sp)
                            }
                            ElevatedButton(
                                onClick = {
                                    host = "crosspoint.local"
                                    port = "80"
                                    path = "/upload"
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("X3 Hotspot", fontSize = 11.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            sharedPrefs.edit().apply {
                                putString("x3_host", host.trim())
                                putString("x3_port", port.trim())
                                putString("x3_path", path.trim())
                                putString("x3_folder", folder.trim())
                            }.apply()
                            showSettingsDialog = false
                            Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                    ) {
                        Text("Save", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Cancel", color = primaryColor)
                    }
                }
            )
        }

        MaterialTheme(
            colorScheme = lightColorScheme(
                background = backgroundColor,
                surface = cardColor,
                primary = primaryColor,
                onBackground = textColor,
                onSurface = textColor
            )
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                "InkPress",
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = primaryColor,
                                letterSpacing = 0.5.sp
                            )
                        },
                        actions = {
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = primaryColor
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = backgroundColor
                        )
                    )
                },
                containerColor = backgroundColor
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        // Description
                        Text(
                            "Convert any webpage into a minimal, clean, image-free EPUB document optimized for your Xteink X3 e-reader.",
                            fontSize = 14.sp,
                            color = secondaryTextColor,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        // Manual input
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            label = { Text("Paste article URL here") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                focusedLabelColor = primaryColor
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (inputUrl.isNotBlank()) {
                                    startConversion(inputUrl)
                                    inputUrl = ""
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                        ) {
                            Text("Generate EPUB", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // History section header with Batch Push option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Recent Conversions",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = primaryColor
                            )
                            if (conversionList.isNotEmpty()) {
                                TextButton(
                                    onClick = { pushAllToX3() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = primaryColor
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Push All to X3", fontSize = 12.sp, color = primaryColor, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (conversionList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No articles converted yet.\nTry sharing a link from Chrome!",
                                    color = secondaryTextColor,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(conversionList) { item ->
                                    ConversionCard(item, primaryColor, textColor, secondaryTextColor)
                                }
                            }
                        }
                    }

                    // Overlay Dialog for background work
                    AnimatedVisibility(
                        visible = isConverting.value,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .padding(36.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = primaryColor)
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    statusMessage.value,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = textColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ConversionCard(
        item: ConversionItem,
        primaryColor: Color,
        textColor: Color,
        secondaryTextColor: Color
    ) {
        val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
        val dateString = dateFormat.format(Date(item.timestamp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Source: ${item.url}",
                    fontSize = 12.sp,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saved to: ${item.filePath}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dateString,
                        fontSize = 11.sp,
                        color = secondaryTextColor
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Push button
                        TextButton(
                            onClick = { pushToX3(item) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Push",
                                modifier = Modifier.size(12.dp),
                                tint = primaryColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Push to X3", fontSize = 11.sp, color = primaryColor, fontWeight = FontWeight.Bold)
                        }

                        // Delete button
                        TextButton(
                            onClick = { deleteItem(item) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFFC0392B)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", fontSize = 11.sp, color = Color(0xFFC0392B), fontWeight = FontWeight.Bold)
                        }
                    }

                    // Status message
                    val status = uploadStatuses[item.filePath] ?: "Ready"
                    val statusColor = when {
                        status.startsWith("Success") -> Color(0xFF27AE60) // Green
                        status.startsWith("Failed") -> Color(0xFFC0392B) // Red
                        status == "Uploading..." -> Color(0xFF2980B9) // Blue
                        else -> secondaryTextColor
                    }
                    Text(
                        text = status,
                        fontSize = 11.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 4.dp).widthIn(max = 120.dp)
                    )
                }
            }
        }
    }
}
