package com.selfapps.guardlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfapps.guardlock.data.UsageDay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// Blue vs orange: distinct for the common kinds of colour blindness, and every bar also carries
// its value as text, so colour is never the only signal.
private val MeColor = Color(0xFF1E6FD9)
private val PeerColor = Color(0xFFE67E22)

private fun formatMs(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m" else "${m}m"
}

// ============================================================ tab 3: compare

@Composable
fun CompareTab(vm: MainViewModel) {
    val pair by vm.pair.collectAsState()
    val me by vm.myUsage.collectAsState()
    val peer by vm.peerUsage.collectAsState()
    val peerHealth by vm.peerHealth.collectAsState()

    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val meByDate = me.associateBy { it.date }
    val peerByDate = peer.associateBy { it.date }
    val meToday = meByDate[today.toString()]
    val peerToday = peerByDate[today.toString()]

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Screen time", style = MaterialTheme.typography.headlineMedium) }
        item { Legend() }

        if (pair?.joinerUid == null) {
            item { Text("Charts appear once your friend has joined.") }
        }
        if (me.isEmpty()) {
            item {
                Text(
                    "No screen time from this phone yet. On the My phone tab, finish setup → Screen time. " +
                        "It can take a few minutes for the first numbers to arrive.",
                    color = Red,
                )
            }
        }
        if (pair?.joinerUid != null && peerHealth?.usageAccess == false) {
            item { Text("Your partner has not turned on Screen time access yet, so their bars are empty.") }
        }

        item {
            ChartCard("Today") {
                TotalBar("You", meToday?.totalMs ?: 0L, MeColor, maxOf(meToday?.totalMs ?: 0L, peerToday?.totalMs ?: 0L))
                Spacer(Modifier.height(8.dp))
                TotalBar("Partner", peerToday?.totalMs ?: 0L, PeerColor, maxOf(meToday?.totalMs ?: 0L, peerToday?.totalMs ?: 0L))
            }
        }

        item {
            ChartCard("Last 7 days") {
                WeekChart(
                    days = days,
                    me = days.map { meByDate[it.toString()]?.totalMs ?: 0L },
                    peer = days.map { peerByDate[it.toString()]?.totalMs ?: 0L },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Average per day:  You ${formatMs(avg(days, meByDate))}   Partner ${formatMs(avg(days, peerByDate))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            ChartCard("Top apps today") { TopApps(meToday, peerToday) }
        }
    }
}

private fun avg(days: List<LocalDate>, byDate: Map<String, UsageDay>): Long {
    // Count only days that have data, so a missing day is not treated as zero screen time.
    val vals = days.mapNotNull { byDate[it.toString()]?.totalMs }
    return if (vals.isEmpty()) 0L else vals.sum() / vals.size
}

// ============================================================ pieces

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(MeColor))
        Text("  You", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(20.dp))
        Box(Modifier.size(12.dp).clip(CircleShape).background(PeerColor))
        Text("  Partner", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** One horizontal bar with its label on the left and its value on the right. */
@Composable
private fun TotalBar(label: String, ms: Long, color: Color, maxMs: Long) {
    val frac = if (maxMs <= 0) 0f else (ms.toFloat() / maxMs).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).height(16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
        Text(
            formatMs(ms),
            Modifier.width(64.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Two bars per day (you, partner), shared scale, day letter underneath. */
@Composable
private fun WeekChart(days: List<LocalDate>, me: List<Long>, peer: List<Long>) {
    val max = maxOf(me.maxOrNull() ?: 0L, peer.maxOrNull() ?: 0L)
    val barArea = 120.dp
    Text(
        if (max > 0) "Tallest bar: ${formatMs(max)}" else "No data yet",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, date ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.height(barArea),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DayBar(me[i], max, MeColor)
                    DayBar(peer[i], max, PeerColor)
                }
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DayBar(ms: Long, max: Long, color: Color) {
    val frac = if (max <= 0 || ms <= 0) 0f else (ms.toFloat() / max).coerceIn(0.02f, 1f)
    Box(
        Modifier
            .width(12.dp)
            .fillMaxHeight(frac)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(color)
    )
}

private data class AppRow(val name: String, val me: Long, val peer: Long)

/** Up to 6 apps, ranked by combined time, with both people's bars side by side. */
@Composable
private fun TopApps(me: UsageDay?, peer: UsageDay?) {
    val mine = me?.apps.orEmpty().associateBy { it.pkg }
    val theirs = peer?.apps.orEmpty().associateBy { it.pkg }
    val rows = (mine.keys + theirs.keys).map { pkg ->
        AppRow(
            name = (mine[pkg] ?: theirs[pkg])?.name ?: pkg,
            me = mine[pkg]?.ms ?: 0L,
            peer = theirs[pkg]?.ms ?: 0L,
        )
    }.sortedByDescending { it.me + it.peer }.take(6)

    if (rows.isEmpty()) {
        Text("No app data for today yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = rows.maxOf { maxOf(it.me, it.peer) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            Column {
                Text(r.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                TotalBar("You", r.me, MeColor, max)
                Spacer(Modifier.height(2.dp))
                TotalBar("Partner", r.peer, PeerColor, max)
            }
        }
    }
}
