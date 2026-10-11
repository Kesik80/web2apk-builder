package com.web2apk.builder.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

object IconMaker {
    fun toPng(context: Context, uri: Uri, size: Int = 192): ByteArray {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read the image")
        return fromBytes(data, size)
    }

    /** Crops an image to a centered square and scales it to a launcher-icon PNG. */
    fun fromBytes(data: ByteArray, size: Int = 192): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size * 2 && bounds.outHeight / (sample * 2) >= size * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            ?: throw IOException("Cannot decode the image")

        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}