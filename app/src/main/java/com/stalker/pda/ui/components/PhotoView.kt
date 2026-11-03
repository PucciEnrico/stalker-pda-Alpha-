package com.stalker.pda.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows a photo from a file, downsampled and rotated by EXIF. */
@Composable
fun PhotoView(path: String, modifier: Modifier = Modifier, maxPx: Int = 900) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, maxPx) }
    }
    Box(modifier = modifier.background(PdaColors.PanelAlt), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text("...", style = PdaType.Tiny)
        }
    }
}

private fun decodeSampled(path: String, maxPx: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
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
        bmp.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
