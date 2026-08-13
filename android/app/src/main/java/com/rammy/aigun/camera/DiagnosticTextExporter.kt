package com.rammy.aigun.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object DiagnosticTextExporter {
    fun export(context: Context, text: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Rammy AI Gun",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create the diagnostics file")
            try {
                resolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(text) }
                    ?: error("Unable to write the diagnostics file")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
                return "Downloads/Rammy AI Gun/$FILE_NAME"
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: error("External app storage is unavailable")
        val directory = File(root, "Rammy AI Gun").apply { mkdirs() }
        val output = File(directory, FILE_NAME)
        output.writeText(text)
        return output.absolutePath
    }

    private const val FILE_NAME = "uvc-diagnostics.txt"
}
