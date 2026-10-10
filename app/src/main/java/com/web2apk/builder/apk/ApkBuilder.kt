package com.web2apk.builder.apk

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

object ApkBuilder {
    /** Step 3 test: sign the bundled template as-is and save it to Downloads. */
    fun buildTestApk(context: Context): String {
        val unsigned = File(context.cacheDir, "template.apk")
        context.assets.open("template.apk").use { input ->
            unsigned.outputStream().use { input.copyTo(it) }
        }
        val signed = File(context.cacheDir, "signed.apk")
        if (signed.exists()) signed.delete()
        ApkSign.sign(unsigned, signed)
        return saveToDownloads(context, signed, "web2apk_test.apk")
    }

    private fun saveToDownloads(context: Context, file: File, name: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create file in Downloads")
        val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot write file")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        return "Downloads/$name"
    }
}