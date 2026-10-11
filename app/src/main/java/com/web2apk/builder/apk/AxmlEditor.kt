package com.web2apk.builder.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal editor for a compiled binary AndroidManifest.xml: replaces whole strings in its string pool. */
object AxmlEditor {

    fun replaceStrings(data: ByteArray, replacements: Map<String, String>): ByteArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getShort(0).toInt() == 0x0003) { "Not a binary XML file" }
        val pos = buf.getShort(2).toInt() and 0xFFFF
        require(buf.getShort(pos).toInt() == 0x0001) { "String pool not found" }
        val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
        val poolSize = buf.getInt(pos + 4)
        val stringCount = buf.getInt(pos + 8)
        val styleCount = buf.getInt(pos + 12)
        val flags = buf.getInt(pos + 16)
        val stringsStart = buf.getInt(pos + 20)
        require(styleCount == 0) { "Styled strings are not supported" }
        val utf8 = (flags and 0x100) != 0

        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = buf.getInt(pos + headerSize + i * 4)
            strings.add(readString(data, pos + stringsStart + off, utf8))
        }

        val body = ByteArrayOutputStream()
        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = body.size()
            writeString(body, replacements[strings[i]] ?: strings[i], utf8)
        }
        while (body.size() % 4 != 0) body.write(0)

        val newStringsStart = headerSize + stringCount * 4
        val newPoolSize = newStringsStart + body.size()
        val pool = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.put(data, pos, headerSize)
        pool.putInt(4, newPoolSize)
        pool.putInt(16, flags and 0x1.inv())
        pool.putInt(20, newStringsStart)
        pool.position(headerSize)
        for (o in offsets) pool.putInt(o)
        pool.put(body.toByteArray())

        val rest = data.size - (pos + poolSize)
        val out = ByteBuffer.allocate(pos + newPoolSize + rest).order(ByteOrder.LITTLE_ENDIAN)
        out.put(data, 0, pos)
        out.put(pool.array())
        out.put(data, pos + poolSize, rest)
        out.putInt(4, out.capacity())
        return out.array()
    }

    private fun readString(d: ByteArray, start: Int, utf8: Boolean): String {
        var p = start
        if (utf8) {
            val first = d[p++].toInt() and 0xFF
            if ((first and 0x80) != 0) p++
            val b = d[p++].toInt() and 0xFF
            var len = b
            if ((b and 0x80) != 0) {
                len = ((b and 0x7F) shl 8) or (d[p++].toInt() and 0xFF)
            }
            return String(d, p, len, Charsets.UTF_8)
        }
        var len = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
        p += 2
        if ((len and 0x8000) != 0) {
            val low = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            len = ((len and 0x7FFF) shl 16) or low
        }
        return String(d, p, len * 2, Charsets.UTF_16LE)
    }

    private fun writeString(o: ByteArrayOutputStream, s: String, utf8: Boolean) {
        if (utf8) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeLen8(o, s.length)
            writeLen8(o, bytes.size)
            o.write(bytes)
            o.write(0)
        } else {
            writeLen16(o, s.length)
            o.write(s.toByteArray(Charsets.UTF_16LE))
            o.write(0)
            o.write(0)
        }
    }

    private fun writeLen8(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7F) {
            o.write((n shr 8) or 0x80)
            o.write(n and 0xFF)
        } else {
            o.write(n)
        }
    }

    private fun writeLen16(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7FFF) {
            put16(o, (n shr 16) or 0x8000)
            put16(o, n and 0xFFFF)
        } else {
            put16(o, n)
        }
    }

    private fun put16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }
}