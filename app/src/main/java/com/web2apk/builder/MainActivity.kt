package com.web2apk.builder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.web2apk.builder.apk.ApkBuilder
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

@Composable
fun HomeScreen(version: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var buildStatus by remember { mutableStateOf<String?>(null) }
    var appName by remember { mutableStateOf("Fahrzeit") }
    var appUrl by remember { mutableStateOf("https://fahrzeit.vercel.app") }

    val picker = rememberLauncherForActivityResult(
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
            label = { Text("App name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        OutlinedTextField(
            value = appUrl,
            onValueChange = { appUrl = it },
            label = { Text("Site URL (https)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Button(
            onClick = {
                val url = appUrl.trim()
                if (!url.startsWith("https://")) {
                    buildStatus = "URL must start with https://"
                } else {
                    buildStatus = "Building..."
                    scope.launch {
                        buildStatus = try {
                            val where = withContext(Dispatchers.IO) {
                                ApkBuilder.build(context, appName.trim().ifEmpty { "App" }, url)
                            }
                            "Saved: $where"
                        } catch (e: Throwable) {
                            "Build error: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        ) { Text("Build APK") }

        buildStatus?.let {
            Text(it, modifier = Modifier.padding(top = 12.dp))
        }

        Button(
            onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            modifier = Modifier.padding(top = 16.dp)
        ) { Text("Choose ZIP") }

        error?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        result?.let { r ->
            Text(
                if (r.hasIndex) "index.html found, ${r.files.size} files"
                else "No index.html in the archive root, ${r.files.size} files",
                modifier = Modifier.padding(vertical = 12.dp)
            )
            LazyColumn {
                items(r.files) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}