package com.web2apk.builder.apk

import android.content.Context
import android.net.Uri
import com.android.apksig.SigningCertificateLineage
import com.web2apk.builder.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Signing key storage.
 *
 * The key lives in filesDir/signing.p12 (PKCS12, so it can be exported). Builds before v0.14 used a
 * non-exportable AndroidKeyStore key; on first use that key signs a rotation lineage old -> new, so apps
 * signed with it still update (Android 9+). If the lineage cannot be made, the old key keeps being used.
 */
object SigningKeys {
    class Signer(
        val key: PrivateKey,
        val cert: X509Certificate,
        val lineage: SigningCertificateLineage?,
        /** The original (pre-rotation) key; apksig needs it as the first signer when a lineage is used. */
        val oldest: Pair<PrivateKey, X509Certificate>? = null
    )

    private const val LEGACY_ALIAS = "web2apk_signing_key"
    private const val ALIAS = "web2apk"
    private val INTERNAL_PW = "web2apk-internal".toCharArray()
    private const val BACKUP_NAME = "web2apk_key_backup.zip"
    private const val ENTRY_KEY = "key.p12"
    private const val ENTRY_LINEAGE = "lineage.bin"

    private fun p12File(c: Context) = File(c.filesDir, "signing.p12")
    private fun lineageFile(c: Context) = File(c.filesDir, "signing.lineage")

    private fun readP12(bytes: ByteArray, pw: CharArray): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(bytes), pw)
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IOException("No key in file")
        val key = ks.getKey(alias, pw) as? PrivateKey ?: throw IOException("No key in file")
        val cert = ks.getCertificate(alias) as? X509Certificate ?: throw IOException("No certificate in file")
        return key to cert
    }

    private fun writeP12(key: PrivateKey, cert: X509Certificate, pw: CharArray): ByteArray {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, key, pw, arrayOf(cert))
        val out = ByteArrayOutputStream()
        ks.store(out, pw)
        return out.toByteArray()
    }

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun legacyKey(): Pair<PrivateKey, X509Certificate>? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(LEGACY_ALIAS)) return null
            (ks.getKey(LEGACY_ALIAS, null) as PrivateKey) to (ks.getCertificate(LEGACY_ALIAS) as X509Certificate)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun load(context: Context): Signer {
        val pf = p12File(context)
        if (pf.isFile) {
            val (k, c) = readP12(pf.readBytes(), INTERNAL_PW)
            val lf = lineageFile(context)
            val lineage = if (lf.isFile) SigningCertificateLineage.readFromFile(lf) else null
            return Signer(k, c, lineage, if (lineage != null) legacyKey() else null)
        }

        val legacy = legacyKey()
        val kp = generate()
        val cert = CertMaker.selfSigned(kp, "Web2APK Builder", 30)
        var lineage: SigningCertificateLineage? = null
        if (legacy != null) {
            try {
                lineage = SigningCertificateLineage.Builder(
                    SigningCertificateLineage.SignerConfig.Builder(legacy.first, legacy.second).build(),
                    SigningCertificateLineage.SignerConfig.Builder(kp.private, cert).build()
                ).build()
            } catch (e: Exception) {
                // Keep signing with the old key rather than break updates of existing apps.
                return Signer(legacy.first, legacy.second, null)
            }
        }
        val lf = lineageFile(context)
        try {
            if (lineage != null) lineage.writeToFile(lf) else lf.delete()
            pf.writeBytes(writeP12(kp.private, cert, INTERNAL_PW))
        } catch (e: Exception) {
            lf.delete()
            pf.delete()
            if (legacy != null) return Signer(legacy.first, legacy.second, null)
            throw e
        }
        return Signer(kp.private, cert, lineage, legacy)
    }

    /** Suggested file name for the backup. */
    const val BACKUP_FILE_NAME = BACKUP_NAME

    /** Builds a password-protected backup (key + lineage) as zip bytes; the caller decides where to save it. */
    fun buildBackup(context: Context, password: CharArray): ByteArray {
        val signer = load(context)
        if (!p12File(context).isFile) throw IOException(context.getString(R.string.key_legacy))
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { z ->
            z.putNextEntry(ZipEntry(ENTRY_KEY))
            z.write(writeP12(signer.key, signer.cert, password))
            z.closeEntry()
            val lf = lineageFile(context)
            if (lf.isFile) {
                z.putNextEntry(ZipEntry(ENTRY_LINEAGE))
                z.write(lf.readBytes())
                z.closeEntry()
            }
        }
        return zipBytes.toByteArray()
    }

    /** Replaces the current signing key with the one from a backup file. */
    @Synchronized
    fun restoreBackup(context: Context, uri: Uri, password: CharArray) {
        var keyBytes: ByteArray? = null
        var lineageBytes: ByteArray? = null
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { s ->
            ZipInputStream(s).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val data = z.readBytes()
                    if (data.size > 1_000_000) throw IOException("Bad backup file")
                    when (e.name) {
                        ENTRY_KEY -> keyBytes = data
                        ENTRY_LINEAGE -> lineageBytes = data
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
        }
        val kb = keyBytes ?: throw IOException(context.getString(R.string.key_bad_file))
        val (k, c) = try {
            readP12(kb, password)
        } catch (e: IOException) {
            throw IOException(context.getString(R.string.key_wrong_password))
        }

        val lf = lineageFile(context)
        val lb = lineageBytes
        if (lb != null) {
            val tmp = File(context.cacheDir, "lineage.tmp")
            tmp.writeBytes(lb)
            SigningCertificateLineage.readFromFile(tmp) // validates
            tmp.copyTo(lf, overwrite = true)
            tmp.delete()
        } else {
            lf.delete()
        }
        p12File(context).writeBytes(writeP12(k, c, INTERNAL_PW))
    }
}
