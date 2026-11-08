package com.selfapps.guardlock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfapps.guardlock.lock.LockEngine
import kotlinx.coroutines.delay

/** Full-screen cover shown over a blocked app. Back and "Go home" both leave to the launcher. */
class LockActivity : ComponentActivity() {
    private var pkg by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LockScreen(pkg = pkg, onHome = ::goHome, onUnlocked = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}

@Composable
private fun LockScreen(pkg: String, onHome: () -> Unit, onUnlocked: () -> Unit) {
    BackHandler(onBack = onHome)

    // If the guardian unlocks while this screen is up, drop back into the app automatically.
    LaunchedEffect(pkg) {
        while (true) {
            delay(1000)
            if (!LockEngine.isBlocked(pkg)) {
                onUnlocked()
                break
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "${LockEngine.label(pkg)} is locked",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text("Your partner controls this lock.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onHome) { Text("Go home") }
    }
}
