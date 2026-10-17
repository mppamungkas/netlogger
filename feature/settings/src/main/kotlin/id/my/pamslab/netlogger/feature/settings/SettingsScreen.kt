package id.my.pamslab.netlogger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.data.CaptureSettings
import id.my.pamslab.netlogger.feature.settings.navigation.AboutRoute
import id.my.pamslab.netlogger.feature.settings.navigation.CapabilitiesRoute
import id.my.pamslab.netlogger.feature.settings.navigation.CaptureSettingsRoute
import id.my.pamslab.netlogger.feature.settings.navigation.HelpRoute
import id.my.pamslab.netlogger.feature.settings.navigation.ProblemsRoute
import id.my.pamslab.netlogger.feature.settings.navigation.LoggingSettingsRoute
import id.my.pamslab.netlogger.feature.settings.navigation.SpeedTestSettingsRoute
import java.net.URI

/** The Settings list: one entry per page, each with its current value (decision 003). */
@Composable
internal fun SettingsScreen(onOpen: (Any) -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val speedTest by viewModel.speedTest.collectAsStateWithLifecycle()
    val interval by viewModel.intervalMs.collectAsStateWithLifecycle()
    val scenario by viewModel.defaultScenario.collectAsStateWithLifecycle()
    val captureAll by viewModel.captureAllApps.collectAsStateWithLifecycle()
    val captureApps by viewModel.captureSelectedApps.collectAsStateWithLifecycle()
    val captureMtu by viewModel.captureMtu.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.settings_section_measurement), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsEntry(
            stringResource(R.string.settings_logging),
            stringResource(
                R.string.settings_logging_summary,
                interval.toInt(),
                stringResource(scenario?.labelRes() ?: R.string.logging_scenario_last),
            ),
        ) { onOpen(LoggingSettingsRoute) }
        val host = remember(speedTest) { runCatching { URI(speedTest.downloadUrl.trim()).host }.getOrNull() }
        val server = if (speedTest.isComplete) stringResource(R.string.settings_speed_test_summary_server, host ?: speedTest.downloadUrl)
            else stringResource(R.string.settings_speed_test_summary_none)
        SettingsEntry(stringResource(R.string.settings_speed_test), stringResource(R.string.settings_speed_test_summary_public, server)) {
            onOpen(SpeedTestSettingsRoute)
        }
        val mtu = if (captureMtu == CaptureSettings.MTU_FAST) "10 000" else captureMtu.toString()
        SettingsEntry(
            stringResource(R.string.settings_capture),
            when {
                captureAll -> stringResource(R.string.settings_capture_summary_all, mtu)
                captureApps.isEmpty() -> stringResource(R.string.settings_capture_summary_none, mtu)
                else -> pluralStringResource(R.plurals.settings_capture_summary_specific, captureApps.size, captureApps.size, mtu)
            },
        ) { onOpen(CaptureSettingsRoute) }
        Text(stringResource(R.string.settings_section_info), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsEntry(stringResource(R.string.settings_capabilities), stringResource(R.string.settings_capabilities_summary)) {
            onOpen(CapabilitiesRoute)
        }
        SettingsEntry(stringResource(R.string.settings_help), stringResource(R.string.settings_help_summary)) { onOpen(HelpRoute) }
        val problems by viewModel.problems.collectAsStateWithLifecycle()
        SettingsEntry(
            stringResource(R.string.problems_title),
            when {
                problems.isEmpty() -> stringResource(R.string.problems_summary_none)
                problems.unseen() > 0 -> pluralStringResource(R.plurals.problems_summary_new, problems.unseen(), problems.unseen())
                else -> pluralStringResource(R.plurals.problems_summary, problems.size, problems.size)
            },
        ) { onOpen(ProblemsRoute) }
        val context = LocalContext.current
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
        SettingsEntry(stringResource(R.string.settings_about), stringResource(R.string.settings_about_summary, version)) { onOpen(AboutRoute) }
    }
}
