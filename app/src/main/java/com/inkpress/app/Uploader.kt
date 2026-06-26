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
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Synchronously pushes an EPUB file to the Xteink X3 device.
     * Should be called from a background thread (e.g. via Coroutines Dispatchers.IO).
     */
    fun pushFile(
        filePath: String,
        host: String,
        port: Int,
        uploadPath: String,
        folder: String
    ): Result<Unit> {
        return runCatching {
            val fileName = filePath.substringAfterLast("/")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "InkPress/$fileName")

            if (!file.exists()) {
                throw IOException("File does not exist at local path: ${file.absolutePath}")
            }

            // Clean host configuration
            var cleanHost = host.trim()
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
                // Common parameter keys for destination folders in Web Transfer servers
                .addFormDataPart("folder", folder)
                .addFormDataPart("path", folder)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error ${response.code}: ${response.message}")
                }
            }
        }
    }
}
