package com.web2apk.builder.apk

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File

object ApkSign {
    fun sign(context: Context, input: File, output: File) {
        val signer = SigningKeys.load(context)
        // With a rotation lineage, apksig wants the oldest key first (it signs v2); the new key follows (v3).
        // If the old key is gone (new phone, reinstalled Builder), sign with the new key alone.
        val oldest = signer.oldest
        val lineage = signer.lineage
        val useLineage = lineage != null && oldest != null
        val configs = mutableListOf<ApkSigner.SignerConfig>()
        if (useLineage) {
            configs += ApkSigner.SignerConfig.Builder("web2apk-old", oldest!!.first, listOf(oldest.second)).build()
        }
        configs += ApkSigner.SignerConfig.Builder("web2apk", signer.key, listOf(signer.cert)).build()
        val builder = ApkSigner.Builder(configs)
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
        // Lineage lets apps signed with the old (AndroidKeyStore) key be updated by the new key.
        if (useLineage) builder.setSigningCertificateLineage(lineage!!)
        builder.build().sign()
    }
}
