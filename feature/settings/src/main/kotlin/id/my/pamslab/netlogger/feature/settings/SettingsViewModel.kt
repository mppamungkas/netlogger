package id.my.pamslab.netlogger.feature.settings

import android.security.NetworkSecurityPolicy
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.CaptureSettings
import id.my.pamslab.netlogger.core.data.LoggingSettings
import id.my.pamslab.netlogger.core.data.SpeedTestSettings
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import id.my.pamslab.netlogger.core.diagnostics.Problem
import id.my.pamslab.netlogger.core.diagnostics.ProblemReports
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SpeedTestSettings,
    logging: LoggingSettings,
    capture: CaptureSettings,
    problemReports: ProblemReports,
) : ViewModel() {
    val problems: StateFlow<List<Problem>> = problemReports.reports
    val captureAllApps: StateFlow<Boolean> = capture.allApps
    val captureSelectedApps: StateFlow<List<String>> = capture.selectedApps
    val captureMtu: StateFlow<Int> = capture.nativeMtu
    val intervalMs: StateFlow<Long> = logging.intervalMs
    val defaultScenario: StateFlow<Scenario?> = logging.defaultScenario
    val speedTest: StateFlow<SpeedTestConfig> = settings.config

    fun save(pingUrl: String, downloadUrl: String, uploadUrl: String) = settings.save(pingUrl, downloadUrl, uploadUrl)


    /** Hosts of plain-HTTP URLs this build does not allow (network security config); empty when all are fine. */
    fun cleartextBlocked(urls: List<String>): List<String> =
        cleartextBlockedHosts(urls) { NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(it) }
}

/**
 * Hosts among [urls] that use plain HTTP but may not ([permitted]). HTTPS URLs and unparsable text are ignored
 * (the latter fail later with "invalid URL").
 */
internal fun cleartextBlockedHosts(urls: List<String>, permitted: (String) -> Boolean): List<String> =
    urls.map(String::trim)
        .filter { it.startsWith("http://", ignoreCase = true) }
        .mapNotNull { runCatching { URI(it).host }.getOrNull() }
        .distinct()
        .filterNot(permitted)
