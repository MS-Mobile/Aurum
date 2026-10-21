package com.msmobile.aurum.capture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.msmobile.aurum.util.DateTimeProvider
import com.msmobile.aurum.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Saves processed photos to `Pictures/Aurum` through MediaStore. */
class PhotoGallery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dateTimeProvider: DateTimeProvider,
    private val dispatcherProvider: DispatcherProvider,
) {
    /** The most recent photo in the album that this app can see (the ones it saved), or null. */
    suspend fun latestPhoto(): Uri? = withContext(dispatcherProvider.io) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM/"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    /**
     * Saves [jpeg] (already carrying the original EXIF) after marking it upright and fixing its dimensions,
     * since the pixels were rotated before processing.
     */
    fun save(jpeg: ByteArray, width: Int, height: Int): Uri {
        val now = dateTimeProvider.nowLocalDateTime()
        val name = "AURUM_${now.format(FILE_NAME_FORMAT)}.jpg"
        val temp = File.createTempFile("aurum", ".jpg", context.cacheDir)
        try {
            temp.writeBytes(jpeg)
            ExifInterface(temp).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
                if (hasAttribute(ExifInterface.TAG_IMAGE_WIDTH)) setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                if (hasAttribute(ExifInterface.TAG_IMAGE_LENGTH)) setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                saveAttributes()
            }
            return insert(temp, name)
        } finally {
            temp.delete()
        }
    }

    private fun insert(file: File, name: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTimeProvider.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Could not open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private companion object {
        const val ALBUM = "Aurum"
        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
    }
}
