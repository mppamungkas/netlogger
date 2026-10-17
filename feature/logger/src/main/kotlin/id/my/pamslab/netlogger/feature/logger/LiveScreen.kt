package id.my.pamslab.netlogger.feature.logger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.diagnostics.Problem
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.NetworkRoute
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.ui.signal.NetworkColors
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestTarget
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen
import id.my.pamslab.netlogger.feature.logger.service.LoggingService
import id.my.pamslab.netlogger.feature.logger.service.LoggingState
import id.my.pamslab.netlogger.feature.logger.service.SpeedTestState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The capture row from feature:capture; it draws [label] as its first column. */
typealias CaptureRowSlot = @Composable (label: @Composable () -> Unit) -> Unit

@Composable
internal fun LiveScreen(
    loggingPermissionsMissing: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    captureRow: CaptureRowSlot = {},
    onOpenProblem: (String) -> Unit = {},
) {
    if (loggingPermissionsMissing) {
        Column(modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Logging needs precise location and phone state permissions. " +
                            "Existing sessions and exports still work without them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onRequestPermissions) { Text("Review permissions") }
                }
            }
            PlaceholderScreen(title = "Live", description = "Radio values appear here once permissions are granted.")
        }
    } else {
        // Only created (and sampling) when permissions are present.
        LiveContent(hiltViewModel(), captureRow, onOpenProblem, modifier)
    }
}

@Composable
private fun LiveContent(viewModel: LiveViewModel, captureRow: CaptureRowSlot, onOpenProblem: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LiveUiState.Waiting -> PlaceholderScreen("Live", "Waiting for the first radio sample…", modifier)
        is LiveUiState.Failed -> PlaceholderScreen("Live", "Sampling stopped: ${s.message}", modifier)
        is LiveUiState.Ready -> {
            val scenario by viewModel.scenario.collectAsStateWithLifecycle()
            val intervalMs by viewModel.intervalMs.collectAsStateWithLifecycle()
            val open by viewModel.openSections.collectAsStateWithLifecycle()
            val problem by viewModel.newProblem.collectAsStateWithLifecycle()
            Column(modifier.fillMaxSize()) {
                StatusBar(s, scenario, viewModel::selectScenario, intervalMs)
                problem?.let { ProblemCard(it, onView = { onOpenProblem(it.id) }, onDismiss = { viewModel.dismissProblem(it.id) }) }
                SnapshotView(s, open, viewModel::setOpen, captureRow, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SnapshotView(
    ui: LiveUiState.Ready,
    open: Set<LiveSection>,
    onOpen: (LiveSection, Boolean) -> Unit,
    captureRow: CaptureRowSlot,
    modifier: Modifier = Modifier,
) {
    val snapshot = ui.snapshot
    val serving = snapshot.cells.filter { it.servingInferred }
    // Stale SignalStrength rows have no identity and are not neighbors; they show up as a warning.
    val neighbors = snapshot.cells.filter { !it.servingInferred && it.source == CellSource.CELL_INFO }.sortedByDescending { it.rsrpDbm ?: it.rssiDbm ?: Int.MIN_VALUE }
    val detailsOpen = LiveSection.DETAILS in open

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { NetworkSection(snapshot) }
        item {
            SectionHeader(stringResource(R.string.live_neighbours, neighbors.size), null, LiveSection.NEIGHBOURS in open) {
                onOpen(LiveSection.NEIGHBOURS, it)
            }
        }
        if (LiveSection.NEIGHBOURS in open) item { NeighborRows(neighbors) }
        item { GpsAndDataSection(ui, captureRow) }
        item { EventsSection(ui.events, recording = ui.logging is LoggingState.Running) }
        item {
            SectionHeader(stringResource(R.string.live_details), stringResource(R.string.live_details_hint), detailsOpen) {
                onOpen(LiveSection.DETAILS, it)
            }
        }
        if (detailsOpen) {
            item { NetworkCard(snapshot) }
            item { LocationCard(ui.tick?.location ?: ui.location?.fix, ui.tick?.locationStatus ?: ui.location?.status, ui.locationAgeMs, ui.location?.error) }
            ui.tick?.device?.let { item { DeviceCard(it) } }
            items(serving) { ServingCellCard(it) }
            if (snapshot.warnings.isNotEmpty()) item { WarningsCard(snapshot) }
        }
    }
}

/** After an unexpected end of NetLogger: what happened, View report, Dismiss (decision 005). Shown once per report. */
@Composable
private fun ProblemCard(problem: Problem, onView: () -> Unit, onDismiss: () -> Unit) {
    val time = remember(problem.shownTimeMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(problem.shownTimeMs)) }
    Card(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.live_problem_title, time), style = MaterialTheme.typography.titleSmall)
            problem.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            problem.state?.sessionId?.let { Text(stringResource(R.string.live_problem_recording, it), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onView) { Text(stringResource(R.string.live_problem_view)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.live_problem_dismiss)) }
            }
        }
    }
}

/** Recording state and the session controls; stays above the scrolling sections. */
@Composable
private fun StatusBar(ui: LiveUiState.Ready, scenario: Scenario, onScenario: (Scenario) -> Unit, intervalMs: Long) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val logging = ui.logging) {
                    is LoggingState.Running -> {
                        val st = logging.stats
                        val nowNs = ui.tick?.radio?.elapsedRealtimeNs ?: st.startedElapsedNs
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("● ", color = RecordingRed, fontWeight = FontWeight.Bold)
                                Text(
                                    "${stringResource(R.string.live_rec)} ${formatElapsed(nowNs - st.startedElapsedNs)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            val ticks = if (st.recordedPct < WARN_TICK_SHARE_PCT) {
                                stringResource(R.string.live_ticks_share, st.recorded, st.recordedPct)
                            } else {
                                stringResource(R.string.live_ticks, st.recorded)
                            }
                            val markers = logging.markers.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.live_markers, it, it) }
                            Text(listOfNotNull(ticks, scenario.label(), markers).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { LoggingService.addMarker(context) }) { Text(stringResource(R.string.live_marker)) }
                        Button(onClick = { LoggingService.stop(context) }) { Text(stringResource(R.string.live_stop)) }
                    }
                    is LoggingState.Idle -> {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.live_not_logging), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            logging.lastSession?.let { st ->
                                Text(
                                    stringResource(R.string.live_last_session, st.recorded, st.expected, st.recordedPct),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        ScenarioDropdown(scenario, onScenario)
                        Button(onClick = { LoggingService.start(context, scenario, intervalMs) }) { Text(stringResource(R.string.live_start)) }
                    }
                }
            }
            (ui.logging as? LoggingState.Running)?.writeErrors?.takeIf { it > 0 }?.let {
                Text(stringResource(R.string.live_write_errors, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScenarioDropdown(scenario: Scenario, onScenario: (Scenario) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("${scenario.label()} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Scenario.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label()) },
                    onClick = {
                        expanded = false
                        onScenario(s)
                    },
                )
            }
        }
    }
}

/** A row that opens or closes the section below it. */
@Composable
private fun SectionHeader(title: String, hint: String?, open: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!open) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾ " else "▸ ", style = MaterialTheme.typography.titleSmall)
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!open && hint != null) {
            Text("  $hint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Operator, network type in words and the serving-cell table (decision 004). */
@Composable
private fun NetworkSection(s: RadioSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.operatorName ?: stringResource(R.string.live_no_operator), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    s.operatorNumeric?.let { Text(it.chunked(3).joinToString("-"), style = MaterialTheme.typography.bodySmall) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        networkLabel(s.networkType, s.cellBandwidthsKhz),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NetworkColors.color(s.networkType.name),
                    )
                    val flags = listOfNotNull(
                        stringResource(R.string.live_roaming).takeIf { s.isRoaming == true },
                        dataStateLabel(s.dataState),
                    )
                    if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (s.dataSubscriptionId != s.subscriptionId) {
                Text(
                    stringResource(R.string.live_data_sim_changed, s.dataSubscriptionId.orDash(), s.subscriptionId.orDash()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            s.listenerError?.let { Text(stringResource(R.string.live_listener_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ServingTable(servingColumns(s.cells))
        }
    }
}

@Composable
private fun ServingTable(columns: List<ServingColumn>) {
    if (columns.isEmpty()) {
        Text(stringResource(R.string.live_no_serving), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val levelLabel = if (columns.all { it.rat == Rat.LTE || it.rat == Rat.NR }) "RSRP" else stringResource(R.string.live_signal)
    TableLine(stringResource(R.string.live_serving), columns.map { { Text(it.title(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) } })
    TableLine("PCI / ARFCN", columns.map { c -> { Value("${c.pci.orDash()} / ${c.arfcn.orDash()}", c.stale) } })
    TableLine(levelLabel, columns.map { c -> { Level(c.level, "dBm", c.stale, if (c.rat == Rat.LTE || c.rat == Rat.NR) SignalLevel.rsrp(c.level.value) else null) } })
    TableLine("RSRQ", columns.map { c -> { Level(c.rsrq, "dB", c.stale, null) } })
    TableLine("SINR", columns.map { c -> { Level(c.sinr, "dB", c.stale, SignalLevel.sinr(c.sinr.value)) } })
    TableLine("Cell / TAC", columns.map { c -> { Value(if (c.cellId == null && c.tac == null) "–" else "${c.cellId.orDash()} / ${c.tac.orDash()}", c.stale) } })
    if (columns.any { c -> listOf(c.level, c.rsrq, c.sinr).any { it.fromSignalStrength } }) {
        Text(stringResource(R.string.live_ss_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TableLine(label: String, cells: List<@Composable () -> Unit>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cells.forEach { Box(Modifier.weight(1f)) { it() } }
    }
}

@Composable
private fun Value(text: String, stale: Boolean) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = if (stale) Color.Gray else Color.Unspecified)
}

/** A value with its unit, a colour dot when [level] is given, and "SS" when it came from SignalStrength. */
@Composable
private fun Level(shown: Shown<Int>, unit: String, stale: Boolean, level: SignalLevel?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (level != null && shown.value != null) Text("● ", color = level.color, style = MaterialTheme.typography.bodyMedium)
        Value(shown.value?.let { "$it $unit" } ?: "–", stale && !shown.fromSignalStrength)
        if (shown.fromSignalStrength) Text(" SS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Neighbour cells, strongest first. */
@Composable
private fun NeighborRows(neighbors: List<CellSample>) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        if (neighbors.isEmpty()) {
            Text(stringResource(R.string.live_no_neighbours), style = MaterialTheme.typography.bodySmall)
            return
        }
        TableRow(listOf("RAT", "PCI", "ARFCN", "RSRP", "RSRQ", "Age s"), header = true)
        neighbors.forEach { c ->
            TableRow(
                listOf(
                    c.rat.name, c.pci.orDash(), c.arfcn.orDash(), (c.rsrpDbm ?: c.rscpDbm ?: c.rssiDbm).orDash(), c.rsrqDb.orDash(),
                    c.cellInfoAgeMs?.let { "%.1f".format(Locale.ROOT, it / 1000.0) }.orDash(),
                ),
                stale = c.isStale(),
            )
        }
    }
}

/** GPS in two lines and the data route with its rates (decision 004). */
@Composable
private fun GpsAndDataSection(ui: LiveUiState.Ready, captureRow: CaptureRowSlot) {
    val fix = ui.tick?.location ?: ui.location?.fix
    val status = ui.tick?.locationStatus ?: ui.location?.status
    val stale = (ui.locationAgeMs ?: 0) > STALE_AGE_MS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                RowLabel(stringResource(R.string.live_gps))
                Column {
                    Text(
                        listOfNotNull(status?.label(), fix?.let { fixLine(it, ui.locationAgeMs) }).joinToString(" · ").ifEmpty { "–" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (stale) Color.Gray else Color.Unspecified,
                    )
                    fix?.let {
                        Text(
                            "%.6f, %.6f".format(Locale.ROOT, it.latitude, it.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (stale) Color.Gray else Color.Unspecified,
                        )
                    }
                    ui.location?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row {
                RowLabel(stringResource(R.string.live_data))
                Column {
                    Text(routeText(ui.route, ui.snapshot.operatorName), style = MaterialTheme.typography.bodyMedium)
                    ui.rates?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "↓ ${formatRate(r.downBps)}   ↑ ${formatRate(r.upBps)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "  " + stringResource(R.string.live_rate_window),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            (ui.logging as? LoggingState.Running)?.let { SpeedTestRow(it.speedTest) }
            // Shows itself only while a session records or a capture is ending.
            captureRow { RowLabel(stringResource(R.string.live_capture)) }
        }
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(text, Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.titleSmall)
}

/**
 * The running session's speed test: the last result or the running phase, and three buttons (2026-10-17): Private
 * server (the user's, Settings), Cloudflare (public comparison, always offered) and Stop (keeps the finished phases).
 */
@Composable
private fun SpeedTestRow(test: SpeedTestState?) {
    val context = LocalContext.current
    val running = test is SpeedTestState.Running
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            RowLabel(stringResource(R.string.live_speed_test))
            Column(Modifier.weight(1f)) {
                when (test) {
                    null -> Text(stringResource(R.string.live_speed_not_run), style = MaterialTheme.typography.bodyMedium)
                    is SpeedTestState.Running -> {
                        val p = test.progress
                        Text(
                            listOfNotNull(
                                p?.phase?.name?.lowercase() ?: stringResource(R.string.live_speed_starting),
                                p?.mbps?.let { "%.1f Mbps".format(Locale.ROOT, it) } ?: p?.rttMs?.let { "%.0f ms".format(Locale.ROOT, it) },
                            ).joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is SpeedTestState.Refused -> Text(test.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is SpeedTestState.Done -> {
                        Text(speedTestTarget(test.results), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(speedTestSummary(test.results), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        test.results.mapNotNull { r -> r.error?.let { "${r.type.name.lowercase()}: $it" } }.forEach {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val compact = PaddingValues(horizontal = 8.dp)
            OutlinedButton(onClick = { LoggingService.speedTest(context, SpeedTestTarget.PRIMARY) }, enabled = !running, contentPadding = compact, modifier = Modifier.weight(1.3f)) {
                Text(stringResource(R.string.live_speed_private), maxLines = 1)
            }
            OutlinedButton(onClick = { LoggingService.speedTest(context, SpeedTestTarget.PUBLIC) }, enabled = !running, contentPadding = compact, modifier = Modifier.weight(1.1f)) {
                Text(stringResource(R.string.live_speed_cloudflare), maxLines = 1)
            }
            OutlinedButton(onClick = { LoggingService.stopSpeedTest(context) }, enabled = running, contentPadding = compact, modifier = Modifier.weight(0.8f)) {
                Text(stringResource(R.string.live_speed_stop), maxLines = 1)
            }
        }
    }
}

/** The last events, newest first (decision 004). */
@Composable
private fun EventsSection(events: List<LiveEvent>, recording: Boolean) {
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.live_events), style = MaterialTheme.typography.titleSmall)
            if (events.isEmpty()) {
                Text(
                    stringResource(if (recording) R.string.live_no_events else R.string.live_no_events_preview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            events.forEach { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(time.format(Date(e.wallTimeMs)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(e.type.title(), style = MaterialTheme.typography.bodyMedium)
                        e.detail().takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            if (!recording && events.isNotEmpty()) {
                Text(stringResource(R.string.live_events_preview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun routeText(route: NetworkRoute?, operator: String?): String {
    val base = when (route?.transport) {
        null -> "–"
        NetworkRoute.Transport.CELLULAR -> operator?.let { stringResource(R.string.live_route_cellular_operator, it) } ?: stringResource(R.string.live_route_cellular)
        NetworkRoute.Transport.WIFI -> route.ssid?.let { stringResource(R.string.live_route_wifi_ssid, it) } ?: stringResource(R.string.live_route_wifi)
        NetworkRoute.Transport.ETHERNET -> stringResource(R.string.live_route_ethernet)
        NetworkRoute.Transport.OTHER -> stringResource(R.string.live_route_other)
        NetworkRoute.Transport.NONE -> stringResource(R.string.live_route_none)
    }
    return if (route?.vpn == true) stringResource(R.string.live_route_vpn, base) else base
}

private val LABEL_WIDTH = 80.dp

@Composable
private fun LocationCard(fix: LocationFix?, status: LocationStatus?, ageMs: Long?, error: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("GPS · ${status.orDash()}", style = MaterialTheme.typography.titleSmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (fix != null) {
                val stale = (ageMs ?: 0) > STALE_AGE_MS
                Field("Lat / Lon", "%.6f / %.6f".format(Locale.ROOT, fix.latitude, fix.longitude), stale)
                Field("Accuracy", "${fix.accuracyM?.let { "%.1f".format(Locale.ROOT, it) }.orDash()} m", stale)
                Field("Speed / Bearing", "${fix.speedMps?.let { "%.1f".format(Locale.ROOT, it) }.orDash()} m/s / ${fix.bearingDeg?.let { "%.0f".format(Locale.ROOT, it) }.orDash()}°", stale)
                Field("Altitude", "${fix.altitudeM?.let { "%.0f".format(Locale.ROOT, it) }.orDash()} m", stale)
                Field("Age", "${ageMs.orDash()} ms", stale)
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceContext) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Device", style = MaterialTheme.typography.titleSmall)
            with(d.battery) {
                Field("Battery", "${levelPct.orDash()} % · ${if (isCharging == true) "charging" else if (isCharging == false) "discharging" else "–"}")
                Field("Current / Voltage", "${currentUa.orDash()} µA / ${voltageMv.orDash()} mV")
                Field("Battery temp", "${tempC.orDash()} °C")
            }
            Field("Thermal / Screen", "${d.thermalStatus.orDash()} / ${d.screenOn?.let { if (it) "on" else "off" }.orDash()}")
            with(d.traffic) {
                Field("Total rx / tx", "${rxBytesTotal.orDash()} / ${txBytesTotal.orDash()} B")
                Field("App rx / tx", "${rxBytesApp.orDash()} / ${txBytesApp.orDash()} B")
            }
        }
    }
}

@Composable
private fun NetworkCard(s: RadioSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Network · ${s.networkType.name}", style = MaterialTheme.typography.titleSmall)
            Field("SIM", "subId ${s.subscriptionId.orDash()} · slot ${s.simSlot.orDash()}")
            Field("Service / data", "${s.serviceState.orDash()} / ${s.dataState.orDash()} · roaming ${s.isRoaming.orDash()}")
            with(s.nsaInputs) {
                Field("NSA inputs", "lteServing=$lteServing nrCellInfo=$nrServingCellInfo nrSignal=$nrSignalStrength")
                Field("Display", "dataNetworkType=${dataNetworkType.orDash()} override=${overrideNetworkType.orDash()}")
            }
            Field("Bandwidths kHz", s.cellBandwidthsKhz.ifEmpty { null }?.joinToString().orDash())
            Field("Cell info", "${s.cellInfoOrigin} · ${s.cells.size} cells · ${s.warningCount} warnings" + if (s.parseErrors > 0) " · ${s.parseErrors} parse errors" else "")
        }
    }
}

@Composable
private fun ServingCellCard(c: CellSample) {
    val stale = c.isStale()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${c.rat} serving · ${c.source} · ${c.connectionStatus.orDash()}",
                style = MaterialTheme.typography.titleSmall,
            )
            Field("PCI / ARFCN", "${c.pci.orDash()} / ${c.arfcn.orDash()}", stale)
            Field("Band", "${c.band.orDash()} (${c.bandSource.orDash()}) · ${c.bandwidthKhz.orDash()} kHz", stale)
            Field("Cell ID / TAC", "${c.cellId.orDash()} / ${c.tac.orDash()}", stale)
            Field("PLMN", "${c.mcc.orDash()}-${c.mnc.orDash()}", stale)
            Field("RSRP / RSRQ / SINR", "${c.rsrpDbm.orDash()} / ${c.rsrqDb.orDash()} / ${c.sinrDb.orDash()}", stale)
            Field("RSSI / CQI / TA", "${c.rssiDbm.orDash()} / ${c.cqi.orDash()} / ${c.timingAdvance.orDash()}", stale)
            if (c.csiRsrpDbm != null || c.csiRsrqDb != null || c.csiSinrDb != null) {
                Field("CSI RSRP / RSRQ / SINR", "${c.csiRsrpDbm.orDash()} / ${c.csiRsrqDb.orDash()} / ${c.csiSinrDb.orDash()}", stale)
            }
            if (c.rscpDbm != null || c.ecNoDb != null) Field("RSCP / Ec/No", "${c.rscpDbm.orDash()} / ${c.ecNoDb.orDash()}", stale)
            if (c.source == CellSource.SIGNAL_STRENGTH) {
                Field("Updated", c.cellInfoAgeMs?.let { "%.1f s ago".format(Locale.ROOT, it / 1000.0) }.orDash())
            } else {
                Field("Age", "${c.cellInfoAgeMs.orDash()} ms", stale)
            }
        }
    }
}

@Composable
private fun WarningsCard(s: RadioSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Parse warnings", style = MaterialTheme.typography.titleSmall)
            s.warnings.take(MAX_WARNINGS_SHOWN).forEach { w ->
                Text(
                    "${w.code} ${w.rat.orDash()} pci=${w.pci.orDash()} ${w.field.orDash()}=${w.rawValue.orDash()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, stale: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (stale) Color.Gray else Color.Unspecified,
        )
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, stale: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach {
            Text(
                it,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (header) FontWeight.Bold else null,
                color = if (stale) Color.Gray else Color.Unspecified,
            )
        }
    }
}

private const val MAX_WARNINGS_SHOWN = 10

/** Below this share of expected ticks, the status bar shows the share. */
private const val WARN_TICK_SHARE_PCT = 95.0

private val RecordingRed = Color(0xFFD32F2F)

// SignalStrength only fires on change, so a large age there means "unchanged", not "stale": never grayed out.
private fun CellSample.isStale() = source == CellSource.CELL_INFO && (cellInfoAgeMs ?: 0) > STALE_AGE_MS

private fun Any?.orDash(): String = this?.toString() ?: "–"
