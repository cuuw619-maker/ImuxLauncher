package com.imux.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class CrashActivity : ComponentActivity() {
    companion object { const val EXTRA_MESSAGE = "crash_message" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CrashScreen(
                    log = CrashLogger.read(this),
                    crashMessage = intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
                    onCopy = { copyLog() },
                    onShare = { shareLog() },
                    onRestart = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Imux log", CrashLogger.read(this)))
    }

    private fun shareLog() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Imux Launcher crash log")
            putExtra(Intent.EXTRA_TEXT, CrashLogger.read(this@CrashActivity))
        }, "Share Imux log"))
    }
}

@Composable
private fun CrashScreen(
    log: String,
    crashMessage: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRestart: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Imux recovered from a crash", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The launcher process stopped unexpectedly. The crash was recorded instead of leaving you with a blank screen. Copy or share the log and send it here for diagnosis.",
            style = MaterialTheme.typography.bodyLarge
        )
        if (crashMessage.isNotBlank()) {
            Text("Last exception", style = MaterialTheme.typography.titleMedium)
            Text(crashMessage, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        }
        Text("Runtime log", style = MaterialTheme.typography.titleMedium)
        Text(
            log,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll),
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Text("  Copy log")
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Text("  Share log")
        }
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text("  Restart Imux")
        }
    }
}
