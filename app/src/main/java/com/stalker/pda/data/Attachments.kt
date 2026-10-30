package com.stalker.pda.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.net.URLConnection

/** Photos and documents live inside the app's private storage. */
object Attachments {
    private fun dir(ctx: Context, name: String) = File(ctx.filesDir, name).apply { mkdirs() }

    fun newPhotoFile(ctx: Context): File = File(dir(ctx, "photos"), "IMG_${System.currentTimeMillis()}.jpg")

    fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    /** Copies a picked file into private storage. Returns (file, display name, mime type). */
    fun importUri(ctx: Context, uri: Uri): Triple<File, String, String>? {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "file"
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: "file"
            }
        } catch (_: Exception) {
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(dir(ctx, "attachments"), "${System.currentTimeMillis()}_$safe")
        return try {
            val input = cr.openInputStream(uri) ?: return null
            input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
            Triple(out, name, mime)
        } catch (e: Exception) {
            null
        }
    }

    fun openFile(ctx: Context, path: String) {
        val f = File(path)
        if (!f.exists()) return
        val type = URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(ctx, f), type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(view, "Відкрити").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun avatarDir(ctx: Context): File = dir(ctx, "avatars")

    /** Square, centre-cropped 160 px JPEG (about 8 KB) made from any picture. */
    fun makeAvatar(ctx: Context, srcPath: String, outName: String): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcPath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
            var bmp = BitmapFactory.decodeFile(srcPath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rotation = when (ExifInterface(srcPath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
            if (rotation != 0f) {
                val m = Matrix()
                m.postRotate(rotation)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, 160, 160, true)
            val out = File(avatarDir(ctx), outName)
            out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            out
        } catch (e: Exception) {
            null
        }
    }
}
