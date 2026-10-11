package com.web2apk.builder.apk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object Installer {
    /** Returns a message for the user, or null if the installer was launched. */
    fun install(context: Context): String? {
        val src = File(context.cacheDir, "signed.apk")
        if (!src.exists()) return "Build the APK first"

        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Allow installs from this app, then press Install again"
        }

        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val dst = File(dir, "app.apk")
        src.copyTo(dst, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
}