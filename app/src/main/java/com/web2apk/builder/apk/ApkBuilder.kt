package com.web2apk.builder.apk

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkBuilder {
    private const val TEMPLATE_PACKAGE = "com.web2apk.template"
    private const val TEMPLATE_LABEL = "Web App"
    private val ICON_ENTRY = Regex("res/.*\\.png")

    /** Same URL always gives the same package, so rebuilding updates the app instead of duplicating it. */
    fun packageFor(url: String): String {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(url.trim().lowercase().toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "com.web2apk.app.a$hash"
    }

    /** Builds a signed APK from the bundled template. Returns a description of where it was saved. */
    fun build(context: Context, appName: String, url: String, iconUri: Uri?): String {
        val template = File(context.cacheDir, "template.apk")
        context.assets.open("template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }

        val pkg = packageFor(url)
        val config = JSONObject().put("name", appName).put("url", url).toString()

        var iconSource = "default"
        val iconPng: ByteArray? = if (iconUri != null) {
            iconSource = "chosen"
            IconMaker.toPng(context, iconUri)
        } else {
            val raw = try { IconFetcher.fetch(url) } catch (e: Exception) { null }
            if (raw != null) {
                try {
                    IconMaker.fromBytes(raw).also { iconSource = "from site" }
                } catch (e: Exception) {
                    null
                }
            } else null
        }

        val unsigned = File(context.cacheDir, "unsigned.apk")
        repack(
            template,
            unsigned,
            replacements = mapOf("assets/config.json" to config.toByteArray(Charsets.UTF_8)),
            transforms = mapOf(
                "AndroidManifest.xml" to { raw: ByteArray ->
                    AxmlEditor.replaceStrings(
                        raw,
                        mapOf(TEMPLATE_PACKAGE to pkg, TEMPLATE_LABEL to appName)
                    )
                }
            ),
            iconPng = iconPng
        )

        val signed = File(context.cacheDir, "signed.apk")
        if (signed.exists()) signed.delete()
        ApkSign.sign(unsigned, signed)

        val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "app" }
        val where = saveToDownloads(context, signed, "$slug.apk")
        return "$where ($pkg), icon: $iconSource"
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

    private fun repack(
        template: File,
        output: File,
        replacements: Map<String, ByteArray>,
        transforms: Map<String, (ByteArray) -> ByteArray>,
        iconPng: ByteArray?
    ) {
        val counting = CountingStream(BufferedOutputStream(FileOutputStream(output)))
        var iconReplaced = false
        ZipOutputStream(counting).use { zos ->
            ZipFile(template).use { zf ->
                for (entry in zf.entries()) {
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name.startsWith("META-INF/") || replacements.containsKey(name)) continue
                    val raw = zf.getInputStream(entry).use { it.readBytes() }
                    var data = transforms[name]?.invoke(raw) ?: raw
                    if (iconPng != null && ICON_ENTRY.matches(name)) {
                        data = iconPng
                        iconReplaced = true
                    }
                    putEntry(zos, counting, name, data, stored = name == "resources.arsc")
                }
            }
            for ((name, data) in replacements) {
                putEntry(zos, counting, name, data, stored = false)
            }
        }
        if (iconPng != null && !iconReplaced) {
            throw IOException("Icon entry not found in the template")
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 8-9: no MediaStore.Downloads, use the app's own external folder (no permission needed).
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("No external storage")
            val dst = File(dir, name)
            file.copyTo(dst, overwrite = true)
            return dst.absolutePath
        }
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