package com.rammy.aigun.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MediaStorePublisher(context: Context) {
    private val appContext = context.applicationContext
    val resolver: ContentResolver = appContext.contentResolver

    data class PendingMedia(
        val uri: Uri,
        val legacyPath: String?,
        val mimeType: String,
    )

    fun createPhoto(): PendingMedia = create(
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        displayName = "RAMMY_IMG_${timestamp()}.jpg",
        mimeType = "image/jpeg",
        modernRelativePath = "${Environment.DIRECTORY_PICTURES}/Rammy AI Gun",
        legacyDirectory = Environment.DIRECTORY_PICTURES,
    )

    fun createVideo(): PendingMedia = create(
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        displayName = "RAMMY_VID_${timestamp()}.mp4",
        mimeType = "video/mp4",
        modernRelativePath = "${Environment.DIRECTORY_MOVIES}/Rammy AI Gun",
        legacyDirectory = Environment.DIRECTORY_MOVIES,
    )

    fun publish(media: PendingMedia) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                media.uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } else {
            media.legacyPath?.let { path ->
                MediaScannerConnection.scanFile(appContext, arrayOf(path), arrayOf(media.mimeType), null)
            }
        }
    }

    fun discard(media: PendingMedia) {
        runCatching { resolver.delete(media.uri, null, null) }
    }

    private fun create(
        collection: Uri,
        displayName: String,
        mimeType: String,
        modernRelativePath: String,
        legacyDirectory: String,
    ): PendingMedia {
        val now = System.currentTimeMillis()
        var legacyPath: String? = null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, now / 1_000L)
            put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, modernRelativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(legacyDirectory),
                    "Rammy AI Gun",
                ).apply {
                    check(exists() || mkdirs()) { "Unable to create media directory" }
                }
                legacyPath = File(directory, displayName).absolutePath
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, legacyPath)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: error("Android MediaStore could not create the output file")
        return PendingMedia(uri, legacyPath, mimeType)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
