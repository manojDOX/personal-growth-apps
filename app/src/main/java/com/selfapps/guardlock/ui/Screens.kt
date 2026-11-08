package com.selfapps.guardlock.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.selfapps.guardlock.data.AppRule
import com.selfapps.guardlock.data.Health
import com.selfapps.guardlock.data.InstalledApp
import com.selfapps.guardlock.data.PairRepository
import com.selfapps.guardlock.lock.Apps
import com.selfapps.guardlock.lock.Perms
import com.selfapps.guardlock.service.GuardDeviceAdmin
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)
internal val Red = Color(0xFFC62828)
private val Grey = Color(0xFF757575)

// ============================================================ onboarding

@Composable
fun StartScreen(vm: MainViewModel) {
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GuardLock", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "You and your friend guard each other. Each of you can lock apps on the other's phone, " +
                "and you both see each other's screen time."
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { vm.createPair() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Start - make my pairing code")
        }
        Text(
            "Do this on ONE phone. Then read the code to your friend.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        Text("Already have a code from your friend?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(PairRepository.CODE_LENGTH + 1) },
            label = { Text("${PairRepository.CODE_LENGTH}-character code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.join(code) },
            enabled = !vm.busy && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Pair with my friend") }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
        vm.error?.let { ErrorText(it) }
    }
}

// ============================================================ home (3 tabs)

@Composable
fun HomeScreen(vm: MainViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshMyUsage() }
    val tabs = listOf("My phone" to "📱", "Partner" to "🛡️", "Compare" to "📊")
    val noInsets = WindowInsets(0, 0, 0, 0)

    Scaffold(
        contentWindowInsets = noInsets,
        bottomBar = {
            NavigationBar(windowInsets = noInsets) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = vm.tab == i,
                        onClick = { vm.tab = i },
                        icon = { Text(icon) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                0 -> MyPhoneTab(vm)
                1 -> PartnerTab(vm)
                else -> CompareTab(vm)
            }
        }
    }
}

// ============================================================ tab 1: my phone (I may only add locks)

@Composable
private fun MyPhoneTab(vm: MainViewModel) {
    val apps by vm.myApps.collectAsState()
    val pair by vm.pair.collectAsState()
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        AppPickerScreen(
            existing = apps.map { it.pkg }.toSet(),
            onDone = { chosen ->
                picking = false
                if (chosen.isNotEmpty()) vm.addMyApps(chosen)
            },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("My phone", style = MaterialTheme.typography.headlineMedium) }
        if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.error?.let { e -> item { ErrorText(e) } }
        if (pair?.joinerUid == null) vm.pairId?.let { id -> item { PairCard(id) } }
        item { SetupCard() }
        item { Button(onClick = { picking = true }, Modifier.fillMaxWidth()) { Text("Add apps to lock") } }
        item { Text("Locked on this phone", style = MaterialTheme.typography.titleMedium) }
        if (apps.isEmpty()) item { Text("No apps locked yet.") }
        items(apps, key = { it.pkg }) { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !rule.locked -> "Released by your partner"
                            rule.isTemporarilyOpen() -> "Open until ${clock(rule.unlockUntil!!)}"
                            else -> "Locked"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Text(
                "Only your partner can unlock or remove an app on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (apps.none { it.locked }) {
            item { TextButton(onClick = { vm.unpair() }) { Text("Unpair (dev only)") } }
        }
    }
}

// ============================================================ tab 2: partner's phone (I hold the keys)

@Composable
private fun PartnerTab(vm: MainViewModel) {
    val pair by vm.pair.collectAsState()
    val apps by vm.peerApps.collectAsState()
    val health by vm.peerHealth.collectAsState()
    val joined = pair?.joinerUid != null

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Partner's phone", style = MaterialTheme.typography.headlineMedium) }
        if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.error?.let { e -> item { ErrorText(e) } }

        if (!joined) {
            vm.pairId?.let { id -> item { PairCard(id) } }
            item { Text("Your friend has not joined yet. Read them the code above.") }
        } else {
            item { HealthCard(health) }
            item { Text("Locked on their phone", style = MaterialTheme.typography.titleMedium) }
            if (apps.isEmpty()) {
                item { Text("Nothing yet. Apps your friend chooses to lock will show up here.") }
            }
            items(apps, key = { it.pkg }) { PartnerAppRow(it, vm) }
        }
    }
}

@Composable
private fun PairCard(code: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Your pairing code", style = MaterialTheme.typography.labelLarge)
            Text(
                code.chunked(4).joinToString("-"),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("Waiting for your friend to enter this code…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HealthCard(h: Health?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val ageMin = h?.lastHeartbeat?.let { (now - it) / 60_000 }

    val (label, color) = when {
        h == null || ageMin == null -> "No check-in from their phone yet" to Amber
        ageMin > 45 -> "Offline for $ageMin min - protection may be off" to Red
        !h.accessibilityOn -> "Lock service is switched OFF" to Red
        !h.batteryExempt || !h.deviceAdmin || !h.notificationsOn -> "Protected, but weakened" to Amber
        else -> "Protected" to Green
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            if (h != null && ageMin != null) {
                Spacer(Modifier.height(6.dp))
                Text("Last check-in: ${if (ageMin < 1) "just now" else "$ageMin min ago"}")
                Text(
                    "Lock service ${tick(h.accessibilityOn)}   Battery ${tick(h.batteryExempt)}   " +
                        "Uninstall guard ${tick(h.deviceAdmin)}   Screen time ${tick(h.usageAccess)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (h.hardened) "Strong mode ON: new users, profiles and safe mode are blocked by Android."
                    else "Strong mode off: new users are blocked only on Settings screens (basic).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun tick(ok: Boolean) = if (ok) "✓" else "✗"

private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun PartnerAppRow(rule: AppRule, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    val status = when {
        !rule.locked -> "Unlocked"
        rule.isTemporarilyOpen() -> "Open until ${clock(rule.unlockUntil!!)}"
        else -> "Locked"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = rule.locked, onCheckedChange = { vm.setLocked(rule.pkg, it) })
            }
            Row {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Open for…") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("15 minutes") }, onClick = { menu = false; vm.unlockForMinutes(rule.pkg, 15) })
                        DropdownMenuItem(text = { Text("1 hour") }, onClick = { menu = false; vm.unlockForMinutes(rule.pkg, 60) })
                        DropdownMenuItem(text = { Text("Until midnight") }, onClick = { menu = false; vm.unlockUntilMidnight(rule.pkg) })
                    }
                }
                TextButton(onClick = { vm.remove(rule.pkg) }) { Text("Remove from list") }
            }
        }
    }
}

// ============================================================ setup + picker

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val s = remember(refresh) { Perms.snapshot(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (s.allGood) "Setup complete ✓" else "Finish setup so the lock can't be bypassed",
                style = MaterialTheme.typography.titleMedium,
            )
            SetupRow("Lock service (Accessibility)", s.accessibility) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (!s.accessibility) {
                Text(
                    "If the switch is greyed out on Android 13+: Settings → Apps → GuardLock → ⋮ → " +
                        "Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SetupRow("Screen time (Usage access)", s.usageAccess) {
                ctx.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                )
            }
            SetupRow("Notifications", s.notifications) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }
            SetupRow("Run in background (battery)", s.batteryExempt) {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                )
            }
            SetupRow("Uninstall protection", s.deviceAdmin) {
                ctx.startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, GuardDeviceAdmin::class.java))
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Stops GuardLock being uninstalled without turning this off first.")
                )
            }
            Text(
                if (s.hardened) "Strong mode ON ✓ - Android blocks new users, profiles and safe mode."
                else "Strong mode off (optional): needs a one-time USB command, see the README. " +
                    "Without it, adding users is blocked only on Settings screens.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SetupRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (ok) "✓" else "✗"}  $label", Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Fix") }
    }
}

@Composable
private fun AppPickerScreen(existing: Set<String>, onDone: (List<InstalledApp>) -> Unit) {
    val ctx = LocalContext.current
    val all = remember { Apps.launchable(ctx).filter { it.pkg !in existing } }
    val selected = remember { mutableStateListOf<String>() }
    BackHandler { onDone(emptyList()) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Choose apps to lock",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(all, key = { it.pkg }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (app.pkg in selected) selected.remove(app.pkg) else selected.add(app.pkg) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = app.pkg in selected, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(app.name)
                }
            }
        }
        Text(
            "Once locked, only your partner can unlock or remove an app.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = { onDone(all.filter { it.pkg in selected }) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Lock ${selected.size} app(s)") }
    }
}

// ============================================================ shared

@Composable
internal fun ErrorText(msg: String) {
    Text(msg, color = Red, modifier = Modifier.padding(top = 8.dp))
}
