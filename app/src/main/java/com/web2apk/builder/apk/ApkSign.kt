package com.web2apk.builder.apk

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File

object ApkSign {
    fun sign(context: Context, input: File, output: File) {
        val signer = SigningKeys.load(context)
        val config = ApkSigner.SignerConfig.Builder("web2apk", signer.key, listOf(signer.cert)).build()
        val builder = ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
        // Lineage lets apps signed with the old (AndroidKeyStore) key be updated by the new key.
        signer.lineage?.let { builder.setSigningCertificateLineage(it) }
        builder.build().sign()
    }
}
