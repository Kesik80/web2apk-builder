package com.web2apk.builder.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/** PNG images for one launcher icon: adaptive foreground + solid background, and a legacy fallback. */
class IconSet(val legacy: ByteArray, val foreground: ByteArray, val background: ByteArray)

object IconMaker {
    /** Size of the adaptive foreground layer in the template (108dp at xxxhdpi). */
    const val FOREGROUND_SIZE = 432

    /** Share of the 108dp canvas that the picture takes; the rest is filled with the background color. */
    private const val FOREGROUND_SCALE = 0.70f

    fun readUri(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read the image")

    /** Builds the three icon images from any picture: the picture is cropped to a square
     *  and its edge color becomes the background, so the icon fills the whole launcher shape. */
    fun makeSet(data: ByteArray): IconSet {
        val square = decodeSquare(data, 512)
        val bgColor = edgeColor(square)

        val size = Math.round(FOREGROUND_SIZE * FOREGROUND_SCALE)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val fg = Bitmap.createBitmap(FOREGROUND_SIZE, FOREGROUND_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(fg).drawBitmap(scaled, (FOREGROUND_SIZE - size) / 2f, (FOREGROUND_SIZE - size) / 2f, null)

        val bg = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bg.eraseColor(bgColor)

        val legacy = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        legacy.eraseColor(bgColor)
        Canvas(legacy).drawBitmap(Bitmap.createScaledBitmap(square, 192, 192, true), 0f, 0f, null)

        return IconSet(png(legacy), png(fg), png(bg))
    }

    private fun png(b: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun decodeSquare(data: ByteArray, target: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            ?: throw IOException("Cannot decode the image")
        val side = minOf(src.width, src.height)
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    /** Median color of the opaque pixels along the border; white if the border is mostly transparent. */
    private fun edgeColor(b: Bitmap): Int {
        val w = b.width
        val h = b.height
        val ring = maxOf(1, minOf(w, h) / 50)
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        var total = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onRing = x < ring || y < ring || x >= w - ring || y >= h - ring
                if (!onRing) continue
                total++
                val p = b.getPixel(x, y)
                if (Color.alpha(p) >= 200) {
                    rs.add(Color.red(p))
                    gs.add(Color.green(p))
                    bs.add(Color.blue(p))
                }
            }
        }
        if (rs.size * 2 < total) return Color.WHITE
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return Color.rgb(rs[m], gs[m], bs[m])
    }
}
