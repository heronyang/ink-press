package com.inkpress.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object StorageHelper {

    fun getOutputStreamForEpub(context: Context, fileName: String): Pair<OutputStream, String>? {
        // Clean filename (replace characters not allowed in file names)
        val cleanName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val finalFileName = if (cleanName.endsWith(".epub", ignoreCase = true)) cleanName else "$cleanName.epub"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/InkPress")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, contentValues)
            if (uri != null) {
                val outputStream = resolver.openOutputStream(uri)
                if (outputStream != null) {
                    // We return a wrapper to mark IS_PENDING as 0 when closed
                    val wrappedStream = object : OutputStream() {
                        override fun write(b: Int) = outputStream.write(b)
                        override fun write(b: ByteArray) = outputStream.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = outputStream.write(b, off, len)
                        override fun flush() = outputStream.flush()
                        override fun close() {
                            outputStream.close()
                            val updateValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            resolver.update(uri, updateValues, null, null)
                        }
                    }
                    Pair(wrappedStream, "Downloads/InkPress/$finalFileName")
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            // Legacy Storage API (API 26-28)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val inkPressDir = File(downloadsDir, "InkPress")
            if (!inkPressDir.exists()) {
                inkPressDir.mkdirs()
            }
            val file = File(inkPressDir, finalFileName)
            Pair(FileOutputStream(file), "Downloads/InkPress/$finalFileName")
        }
    }
}
