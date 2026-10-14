package com.web2apk.builder

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.web2apk.builder.apk.ApkBuilder
import com.web2apk.builder.apk.Installer
import com.web2apk.builder.importer.ImportResult
import com.web2apk.builder.importer.ZipImporter
import com.web2apk.builder.ui.theme.Web2APKBuilderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Web2APKBuilderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

private val SCREEN_MODES = listOf(
    "normal" to R.string.screen_normal,
    "nostatus" to R.string.screen_nostatus,
    "immersive" to R.string.screen_immersive
)

private val ORIENTATIONS = listOf(
    "auto" to R.string.rot_auto,
    "portrait" to R.string.rot_portrait,
    "landscape" to R.string.rot_landscape
)

@Composable
fun HomeScreen(version: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var buildStatus by remember { mutableStateOf<String?>(null) }
    var built by remember { mutableStateOf(false) }
    var appName by remember { mutableStateOf("Fahrzeit") }
    var appUrl by remember { mutableStateOf("https://fahrzeit.vercel.app") }
    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var screen by remember { mutableStateOf("normal") }
    var orientation by remember { mutableStateOf("auto") }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    result = withContext(Dispatchers.IO) { ZipImporter.import(context, uri) }
                    error = null
                } catch (e: Exception) {
                    result = null
                    error = e.message ?: e.toString()
                }
            }
        }
    }

    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) iconUri = uri
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("Web2APK Builder", style = MaterialTheme.typography.headlineMedium)
        Text(
            "v$version",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = appName,
            onValueChange = { appName = it },
            label = { Text(stringResource(R.string.field_app_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        OutlinedTextField(
            value = appUrl,
            onValueChange = { appUrl = it },
            label = { Text(stringResource(R.string.field_site_url)) },
            singleLine = true,
            enabled = result == null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            ) { Text(stringResource(R.string.btn_choose_zip)) }
            result?.let { r ->
                Text(
                    stringResource(
                        if (r.hasIndex) R.string.zip_ok else R.string.zip_no_index,
                        r.files.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
                TextButton(onClick = { result = null }) { Text(stringResource(R.string.btn_reset)) }
            }
        }
        Text(
            stringResource(if (result != null) R.string.mode_zip else R.string.mode_url),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Row(modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { iconPicker.launch(arrayOf("image/*")) }) {
                Text(stringResource(R.string.btn_choose_icon))
            }
            Text(
                stringResource(
                    when {
                        iconUri != null -> R.string.icon_chosen
                        result != null -> R.string.icon_src_zip_label
                        else -> R.string.icon_from_site
                    }
                ),
                modifier = Modifier.padding(start = 12.dp)
            )
            if (iconUri != null) {
                TextButton(onClick = { iconUri = null }) { Text(stringResource(R.string.btn_reset)) }
            }
        }

        Button(
            onClick = {
                val i = SCREEN_MODES.indexOfFirst { it.first == screen }
                screen = SCREEN_MODES[(i + 1) % SCREEN_MODES.size].first
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                stringResource(
                    R.string.screen_label,
                    stringResource(SCREEN_MODES.first { it.first == screen }.second)
                )
            )
        }

        Button(
            onClick = {
                val i = ORIENTATIONS.indexOfFirst { it.first == orientation }
                orientation = ORIENTATIONS[(i + 1) % ORIENTATIONS.size].first
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                stringResource(
                    R.string.rot_label,
                    stringResource(ORIENTATIONS.first { it.first == orientation }.second)
                )
            )
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val url = appUrl.trim()
                    val zip = result
                    if (zip != null && !zip.hasIndex) {
                        buildStatus = context.getString(R.string.err_zip_no_index)
                    } else if (zip == null && !url.startsWith("https://")) {
                        buildStatus = context.getString(R.string.err_https)
                    } else {
                        buildStatus = context.getString(R.string.status_building)
                        built = false
                        scope.launch {
                            buildStatus = try {
                                val where = withContext(Dispatchers.IO) {
                                    val name = appName.trim().ifEmpty { "App" }
                                    if (zip != null) {
                                        ApkBuilder.buildFromZip(context, name, iconUri, screen, orientation)
                                    } else {
                                        ApkBuilder.build(context, name, url, iconUri, screen, orientation)
                                    }
                                }
                                built = true
                                context.getString(R.string.status_saved, where)
                            } catch (e: Throwable) {
                                context.getString(
                                    R.string.err_build,
                                    "${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        }
                    }
                }
            ) { Text(stringResource(R.string.btn_build)) }

            Button(
                onClick = {
                    val msg = try {
                        Installer.install(context)
                    } catch (e: Throwable) {
                        context.getString(
                            R.string.err_install,
                            "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                    if (msg != null) buildStatus = msg
                },
                enabled = built,
                modifier = Modifier.padding(start = 8.dp)
            ) { Text(stringResource(R.string.btn_install)) }
        }

        buildStatus?.let {
            Text(it, modifier = Modifier.padding(top = 12.dp))
        }

        error?.let {
            Text(
                stringResource(R.string.err_generic, it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        result?.let { r ->
            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(r.files) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
