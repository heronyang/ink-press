package com.inkpress.app

import android.os.Environment
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Uploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS) // Short timeout for quick local network resolution
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Synchronously pushes an EPUB file to the Xteink X3 device.
     * Sequentially tries each host in a comma-separated list until one succeeds.
     * Should be called from a background thread (e.g. via Coroutines Dispatchers.IO).
     */
    fun pushFile(
        filePath: String,
        hostListString: String,
        port: Int,
        uploadPath: String
    ): Result<Unit> {
        return runCatching {
            val fileName = filePath.substringAfterLast("/")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "InkPress/$fileName")

            if (!file.exists()) {
                throw IOException("File does not exist at local path: ${file.absolutePath}")
            }

            // Split the comma-separated hosts list
            val hosts = hostListString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) {
                throw IOException("No valid host addresses configured")
            }

            var lastException: Exception? = null
            var success = false

            for (host in hosts) {
                try {
                    // Clean host configuration
                    var cleanHost = host
                    if (!cleanHost.startsWith("http://") && !cleanHost.startsWith("https://")) {
                        cleanHost = "http://$cleanHost"
                    }
                    // Strip trailing slash if any
                    if (cleanHost.endsWith("/")) {
                        cleanHost = cleanHost.dropLast(1)
                    }

                    // Format upload endpoint path
                    val cleanPath = if (uploadPath.startsWith("/")) uploadPath else "/$uploadPath"
                    val url = "$cleanHost:$port$cleanPath"

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "file",
                            file.name,
                            file.asRequestBody("application/epub+zip".toMediaTypeOrNull())
                        )
                        .build()

                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            success = true
                        } else {
                            // Read full HTTP error details from response body if available
                            val errorBody = response.body?.string()?.take(500) ?: ""
                            val detailMsg = if (errorBody.isNotBlank()) " - $errorBody" else ""
                            throw IOException("HTTP error ${response.code}: ${response.message}$detailMsg")
                        }
                    }

                    if (success) {
                        break // Exit loop since this host succeeded
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }

            if (!success) {
                throw lastException ?: IOException("Failed to connect to any of the configured hosts: $hostListString")
            }
        }
    }
}
