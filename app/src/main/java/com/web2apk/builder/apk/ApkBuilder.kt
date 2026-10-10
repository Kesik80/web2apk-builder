package com.web2apk.builder.apk

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkBuilder {

    /** Builds a signed APK from the bundled template with the given name and URL. Returns the saved location. */
    fun build(context: Context, appName: String, url: String): String {
        val template = File(context.cacheDir, "template.apk")
        context.assets.open("template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }

        val config = JSONObject().put("name", appName).put("url", url).toString()
        val unsigned = File(context.cacheDir, "unsigned.apk")
        repack(template, unsigned, mapOf("assets/config.json" to config.toByteArray(Charsets.UTF_8)))

        val signed = File(context.cacheDir, "signed.apk")
        if (signed.exists()) signed.delete()
        ApkSign.sign(unsigned, signed)

        val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "app" }
        return saveToDownloads(context, signed, "$slug.apk")
    }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private fun repack(template: File, output: File, replacements: Map<String, ByteArray>) {
        val counting = CountingStream(BufferedOutputStream(FileOutputStream(output)))
        ZipOutputStream(counting).use { zos ->
            ZipFile(template).use { zf ->
                for (entry in zf.entries()) {
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name.startsWith("META-INF/") || replacements.containsKey(name)) continue
                    val data = zf.getInputStream(entry).use { it.readBytes() }
                    putEntry(zos, counting, name, data, stored = name == "resources.arsc")
                }
            }
            for ((name, data) in replacements) {
                putEntry(zos, counting, name, data, stored = false)
            }
        }
    }

    private fun putEntry(
        zos: ZipOutputStream,
        counting: CountingStream,
        name: String,
        data: ByteArray,
        stored: Boolean
    ) {
        val e = ZipEntry(name)
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = e.size
            e.crc = CRC32().apply { update(data) }.value
            // Pad the local header so the file data starts at a 4-byte boundary.
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val base = counting.count + 30 + nameLen + 4
            val pad = ((4 - (base % 4)) % 4).toInt()
            val extra = ByteArray(4 + pad)
            extra[0] = 0x35
            extra[1] = 0xD9.toByte()
            extra[2] = pad.toByte()
            extra[3] = 0
            e.extra = extra
        } else {
            e.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
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