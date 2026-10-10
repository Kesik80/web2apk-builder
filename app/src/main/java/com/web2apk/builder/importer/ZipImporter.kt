package com.web2apk.builder.importer

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

data class ImportResult(val files: List<String>, val hasIndex: Boolean)

object ZipImporter {
    fun import(context: Context, uri: Uri): ImportResult {
        val target = File(context.filesDir, "project")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val root = target.canonicalPath + File.separator

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(target, entry.name)
                    if (!out.canonicalPath.startsWith(root)) {
                        throw SecurityException("Unsafe path in ZIP: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        // If the archive has a single top-level folder, make its content the project root
        val children = target.listFiles()
        if (children != null && children.size == 1 && children[0].isDirectory) {
            val tmp = File(context.filesDir, "project_tmp")
            tmp.deleteRecursively()
            if (children[0].renameTo(tmp)) {
                target.deleteRecursively()
                tmp.renameTo(target)
            }
        }

        val files = target.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(target).invariantSeparatorsPath }
            .sorted()
            .toList()
        return ImportResult(files, files.contains("index.html"))
    }
}