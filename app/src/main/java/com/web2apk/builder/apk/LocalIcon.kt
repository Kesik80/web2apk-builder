package com.web2apk.builder.apk

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** Finds the best icon inside an imported site folder (ZIP mode). */
object LocalIcon {
    private class Candidate(val file: File, val size: Int)

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z][a-zA-Z-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    fun find(root: File): ByteArray? {
        val base = root.canonicalFile
        val index = File(base, "index.html")
        val html = if (index.isFile) index.readText() else ""

        val manifest = mutableListOf<Candidate>()
        val apple = mutableListOf<Candidate>()
        val plain = mutableListOf<Candidate>()
        var manifestFile: File? = null

        for (tag in LINK_TAG.findAll(html)) {
            val attrs = ATTR.findAll(tag.value).associate {
                it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] })
            }
            val rel = attrs["rel"]?.lowercase() ?: continue
            val href = attrs["href"] ?: continue
            val f = resolve(base, base, href) ?: continue
            val type = attrs["type"]?.lowercase() ?: ""
            if (f.name.lowercase().endsWith(".svg") || type.contains("svg")) continue
            val size = parseSize(attrs["sizes"])
            when {
                rel.contains("manifest") -> manifestFile = f
                rel.contains("apple-touch-icon") -> apple += Candidate(f, size)
                rel.contains("icon") -> plain += Candidate(f, size)
            }
        }

        manifestFile?.takeIf { it.isFile }?.let { mf ->
            try {
                val icons = JSONObject(mf.readText()).optJSONArray("icons")
                if (icons != null) {
                    for (i in 0 until icons.length()) {
                        val o = icons.getJSONObject(i)
                        val src = o.optString("src")
                        val type = o.optString("type").lowercase()
                        val purpose = o.optString("purpose", "any")
                        if (src.isEmpty() || type.contains("svg") || src.lowercase().endsWith(".svg")) continue
                        val f = resolve(base, mf.parentFile ?: base, src) ?: continue
                        var size = parseSize(o.optString("sizes"))
                        if (purpose.contains("maskable") && !purpose.contains("any")) size /= 2
                        manifest += Candidate(f, size)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val ordered = manifest.sortedByDescending { it.size } +
                apple.sortedByDescending { it.size } +
                plain.sortedByDescending { it.size }
        for (c in ordered) {
            load(c.file)?.let { return it }
        }
        for (name in listOf(
            "apple-touch-icon.png", "icon-512.png", "icon-192.png", "icons/icon-512.png",
            "icons/icon-192.png", "icon.png", "favicon.png", "favicon.ico"
        )) {
            load(File(base, name))?.let { return it }
        }
        return null
    }

    private fun parseSize(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val first = s.trim().split(Regex("\\s+")).first().lowercase()
        return first.substringBefore('x').toIntOrNull() ?: 0
    }

    /** Resolves a link inside the project; absolute URLs and paths leaving the project are ignored. */
    private fun resolve(root: File, dir: File, href: String): File? {
        val clean = href.trim().substringBefore('#').substringBefore('?')
        if (clean.isEmpty() || clean.contains("://") || clean.startsWith("//") || clean.startsWith("data:")) return null
        val f = try {
            (if (clean.startsWith("/")) File(root, clean.trimStart('/')) else File(dir, clean)).canonicalFile
        } catch (e: Exception) {
            return null
        }
        return if (f.path.startsWith(root.path + File.separator)) f else null
    }

    private fun load(f: File): ByteArray? {
        if (!f.isFile || f.length() > 5_000_000) return null
        val data = try { f.readBytes() } catch (e: Exception) { return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) data else null
    }
}
