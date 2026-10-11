package com.web2apk.builder.apk

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object IconFetcher {
    private class Candidate(val url: String, val size: Int)

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z][a-zA-Z-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /** Returns raw image bytes of the best icon found for the site, or null. */
    fun fetch(pageUrl: String): ByteArray? {
        val html = try { getText(pageUrl) } catch (e: Exception) { return fallbacks(pageUrl) }

        val manifest = mutableListOf<Candidate>()
        val apple = mutableListOf<Candidate>()
        val plain = mutableListOf<Candidate>()
        var manifestUrl: String? = null

        for (tag in LINK_TAG.findAll(html)) {
            val attrs = ATTR.findAll(tag.value).associate {
                it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] })
            }
            val rel = attrs["rel"]?.lowercase() ?: continue
            val href = attrs["href"] ?: continue
            val abs = resolve(pageUrl, href) ?: continue
            val size = parseSize(attrs["sizes"])
            val type = attrs["type"]?.lowercase() ?: ""
            if (abs.substringBefore('?').lowercase().endsWith(".svg") || type.contains("svg")) continue
            when {
                rel.contains("manifest") -> manifestUrl = abs
                rel.contains("apple-touch-icon") -> apple += Candidate(abs, size)
                rel.contains("icon") -> plain += Candidate(abs, size)
            }
        }

        manifestUrl?.let { mUrl ->
            try {
                val icons = JSONObject(getText(mUrl)).optJSONArray("icons")
                if (icons != null) {
                    for (i in 0 until icons.length()) {
                        val o = icons.getJSONObject(i)
                        val src = o.optString("src")
                        val type = o.optString("type").lowercase()
                        val purpose = o.optString("purpose", "any")
                        if (src.isEmpty() || type.contains("svg") || src.lowercase().endsWith(".svg")) continue
                        val abs = resolve(mUrl, src) ?: continue
                        var size = parseSize(o.optString("sizes"))
                        if (purpose.contains("maskable") && !purpose.contains("any")) size /= 2
                        manifest += Candidate(abs, size)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val ordered = manifest.sortedByDescending { it.size } +
                apple.sortedByDescending { it.size } +
                plain.sortedByDescending { it.size }
        for (c in ordered) {
            download(c.url)?.let { return it }
        }
        return fallbacks(pageUrl)
    }

    private fun fallbacks(pageUrl: String): ByteArray? {
        for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
            val abs = resolve(pageUrl, path) ?: continue
            download(abs)?.let { return it }
        }
        return null
    }

    private fun parseSize(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val first = s.trim().split(Regex("\\s+")).first().lowercase()
        return first.substringBefore('x').toIntOrNull() ?: 0
    }

    private fun resolve(base: String, href: String): String? =
        try { URL(URL(base), href.trim()).toString() } catch (e: Exception) { null }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Web2APK")
        return c
    }

    private fun readLimited(c: HttpURLConnection, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw java.io.IOException("Too large")
            }
        }
        return out.toByteArray()
    }

    private fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return String(readLimited(c, 2_000_000), Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): ByteArray? {
        val c = try { open(url) } catch (e: Exception) { return null }
        return try {
            if (c.responseCode !in 200..299) return null
            val data = readLimited(c, 5_000_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) data else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}