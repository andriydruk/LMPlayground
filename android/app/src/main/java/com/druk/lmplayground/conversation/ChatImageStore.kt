package com.druk.lmplayground.conversation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Persistent storage for chat image attachments and their preparation for
 * the vision pipeline. Images are copied into app-private storage
 * (filesDir/chat_images) so they survive the picker URI's lifetime,
 * process death and cache eviction.
 */
class ChatImageStore(private val app: Application) {

    /**
     * Copy the picked image into app-private *persistent* storage
     * (filesDir/chat_images) so it survives the picker URI's lifetime, process
     * death and cache eviction. The absolute path is saved on the message row
     * so reopened conversations keep their images.
     * Returns the persisted file, or null on failure.
     */
    fun persistImageFile(sourceUri: Uri): File? {
        return try {
            val imagesDir = File(app.filesDir, "chat_images")
            imagesDir.mkdirs()
            val destFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            app.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Content URI (via FileProvider) for a persisted chat image, used both for
     * in-app display (Coil) and for handing off to an external viewer.
     */
    fun imageContentUri(file: File): Uri =
        FileProvider.getUriForFile(
            app, "${app.packageName}.fileprovider", file
        )

    // Reads from the already-persisted local file rather than the original
    // picker (content://media/picker/...) URI: those URIs are unreliable to
    // reopen — a second openInputStream can fail even after the first one
    // succeeded, silently dropping the image and producing a text-only turn.
    // The copy made by persistImageFile is always readable.
    fun resizeImageForVision(file: File): ByteArray? {
        val path = file.absolutePath

        // Decode bounds first
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)

        val maxDimension = 768
        val origWidth = options.outWidth
        val origHeight = options.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null
        val scaleFactor = if (max(origWidth, origHeight) > maxDimension) {
            maxDimension.toFloat() / max(origWidth, origHeight)
        } else {
            1f
        }

        // Subsample for memory efficiency
        options.inJustDecodeBounds = false
        options.inSampleSize = (1f / scaleFactor).toInt().coerceAtLeast(1)

        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null

        // Scale to exact target if needed
        val targetW = (origWidth * scaleFactor).toInt().coerceAtLeast(1)
        val targetH = (origHeight * scaleFactor).toInt().coerceAtLeast(1)
        val scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
            val s = bitmap.scale(targetW, targetH)
            bitmap.recycle()
            s
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return out.toByteArray()
    }
}
