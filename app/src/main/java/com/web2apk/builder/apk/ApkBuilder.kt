package com.web2apk.builder.apk

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.web2apk.builder.R
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
    private const val SITE_HOST = "appassets.androidplatform.net"
    private const val MAX_SITE_BYTES = 200L * 1024 * 1024
    private val ICON_ENTRY = Regex("res/.*\\.png")

    /** Same URL always gives the same package, so rebuilding updates the app instead of duplicating it. */
    fun packageFor(url: String): String = packageForKey(url)

    /** ZIP-mode apps have no URL, so the package comes from the app name: same name updates the same app. */
    fun packageForZip(appName: String): String = packageForKey("zip:" + appName.trim())

    private fun packageForKey(key: String): String {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(key.trim().lowercase().toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "com.web2apk.app.a$hash"
    }

    /** Builds a signed APK from the bundled template. Returns a description of where it was saved. */
    fun build(
        context: Context,
        appName: String,
        url: String,
        iconUri: Uri?,
        screen: String,
        orientation: String
    ): String {
        val template = File(context.cacheDir, "template.apk")
        context.assets.open("template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }

        val pkg = packageFor(url)
        val config = JSONObject()
            .put("name", appName)
            .put("url", url)
            .put("screen", screen)
            .put("orientation", orientation)
            .toString()

        var iconSource = context.getString(R.string.icon_src_default)
        val icons: IconSet? = if (iconUri != null) {
            iconSource = context.getString(R.string.icon_src_chosen)
            IconMaker.makeSet(IconMaker.readUri(context, iconUri))
        } else {
            val raw = try { IconFetcher.fetch(url) } catch (e: Exception) { null }
            if (raw != null) {
                try {
                    IconMaker.makeSet(raw).also { iconSource = context.getString(R.string.icon_src_site) }
                } catch (e: Exception) {
                    null
                }
            } else null
        }

        return assemble(context, template, appName, pkg, config, icons, iconSource, null)
    }

    /**
     * ZIP mode: packs the imported project (filesDir/project) into the APK under assets/site.
     * The template serves it from https://appassets.androidplatform.net/.
     */
    fun buildFromZip(
        context: Context,
        appName: String,
        iconUri: Uri?,
        screen: String,
        orientation: String
    ): String {
        val site = File(context.filesDir, "project")
        if (!File(site, "index.html").isFile) throw IOException(context.getString(R.string.err_zip_no_index))

        val template = File(context.cacheDir, "template.apk")
        context.assets.open("template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }

        val pkg = packageForZip(appName)
        val config = JSONObject()
            .put("name", appName)
            .put("url", "https://$SITE_HOST/index.html")
            .put("mode", "zip")
            .put("screen", screen)
            .put("orientation", orientation)
            .toString()

        var iconSource = context.getString(R.string.icon_src_default)
        val icons: IconSet? = if (iconUri != null) {
            iconSource = context.getString(R.string.icon_src_chosen)
            IconMaker.makeSet(IconMaker.readUri(context, iconUri))
        } else {
            val raw = try { LocalIcon.find(site) } catch (e: Exception) { null }
            if (raw != null) {
                try {
                    IconMaker.makeSet(raw).also { iconSource = context.getString(R.string.icon_src_zip) }
                } catch (e: Exception) {
                    null
                }
            } else null
        }

        return assemble(context, template, appName, pkg, config, icons, iconSource, site)
    }

    private fun assemble(
        context: Context,
        template: File,
        appName: String,
        pkg: String,
        config: String,
        icons: IconSet?,
        iconSource: String,
        siteDir: File?
    ): String {
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
            icons = icons,
            siteDir = siteDir
        )

        val signed = File(context.cacheDir, "signed.apk")
        if (signed.exists()) signed.delete()
        ApkSign.sign(context, unsigned, signed)

        val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "app" }
        val where = saveToDownloads(context, signed, "$slug.apk")
        return context.getString(R.string.build_result, where, pkg, iconSource)
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
        icons: IconSet?,
        siteDir: File?
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
                    if (icons != null && ICON_ENTRY.matches(name)) {
                        // Release builds shorten resource paths, so the icon layers are told apart by size:
                        // tiny = solid background, large = adaptive foreground, anything else = legacy icon.
                        val w = imageWidth(raw)
                        data = when {
                            w <= 16 -> icons.background
                            w >= 400 -> icons.foreground
                            else -> icons.legacy
                        }
                        iconReplaced = true
                    }
                    putEntry(zos, counting, name, data, stored = name == "resources.arsc")
                }
            }
            for ((name, data) in replacements) {
                putEntry(zos, counting, name, data, stored = false)
            }
            if (siteDir != null) {
                val root = siteDir.canonicalFile
                var total = 0L
                for (f in root.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                    val rel = f.relativeTo(root).invariantSeparatorsPath
                    if (rel.startsWith("__MACOSX/") || rel.endsWith(".DS_Store") || rel.startsWith(".git/")) continue
                    total += f.length()
                    if (total > MAX_SITE_BYTES) throw IOException("Site is too large (over 200 MB)")
                    putEntry(zos, counting, "assets/site/$rel", f.readBytes(), stored = false)
                }
            }
        }
        if (icons != null && !iconReplaced) {
            throw IOException("Icon entry not found in the template")
        }
    }

    private fun imageWidth(data: ByteArray): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        return o.outWidth
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
