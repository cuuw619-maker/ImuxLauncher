package com.imux.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imux.gamecore.GameVersion
import com.imux.gamecore.VersionManager
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ImuxTheme { LauncherRoot() } }
    }
}

@Composable
private fun ImuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
private fun LauncherRoot() {
    var loading by remember { mutableStateOf(true) }
    var versions by remember { mutableStateOf<List<GameVersion>>(emptyList()) }

    LaunchedEffect(Unit) {
        val manager = VersionManager()
        try {
            val latest = manager.fetchLatest()
            val archives = manager.fetchArchives()
            versions = listOf(latest) + archives
        } catch (_: Exception) {
            // Keep the launcher usable while the network is unavailable.
            versions = listOf(GameVersion("latest", "Current build", isLatest = true))
        } finally {
            manager.close()
            delay(650)
            loading = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (loading) SplashScreen() else MainScreen(versions)
    }
}

@Composable
private fun SplashScreen() {
    val transition = rememberInfiniteTransition(label = "splash")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(modifier = Modifier.rotate(rotation).height(64.dp).fillMaxWidth(.22f)) {
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = MaterialTheme.colorScheme.primary,
                startAngle = 35f,
                sweepAngle = 265f,
                useCenter = false,
                style = stroke,
                topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx())
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Imux Launcher", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(18.dp))
        CircularProgressIndicator(progress = { pulse }, strokeWidth = 3.dp)
    }
}

@Composable
private fun MainScreen(versions: List<GameVersion>) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Minecraft") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Versions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Select a game build to continue", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            }
            items(versions, key = { it.id }) { version -> VersionCard(version) }
            if (versions.size == 1) {
                item {
                    VersionCard(GameVersion("archive-placeholder", "Older versions", isLatest = false))
                }
            }
        }
    }
}

@Composable
private fun VersionCard(version: GameVersion) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (version.isLatest) {
                AssistChip(onClick = {}, label = { Text("Latest") })
                Spacer(Modifier.height(10.dp))
            }
            Text(version.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(version.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
