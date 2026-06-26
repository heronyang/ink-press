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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

                    // Standardize file name
                    val safeTitle = article.title.take(30).trim()
                    val fileName = "${safeTitle}_inkpress"
                    
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

        // Core theme colors (Sleek e-ink styled paper palette)
        val backgroundColor = Color(0xFFF9F9FB)
        val cardColor = Color(0xFFFFFFFF)
        val textColor = Color(0xFF1E2022)
        val primaryColor = Color(0xFF2C3E50) // Slate Blue/Charcoal
        val secondaryTextColor = Color(0xFF7F8C8D)

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

                        // History section header
                        Text(
                            "Recent Conversions",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = primaryColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

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
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
            }
        }
    }
}
