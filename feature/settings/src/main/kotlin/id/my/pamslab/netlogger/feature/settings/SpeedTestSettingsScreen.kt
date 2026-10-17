package id.my.pamslab.netlogger.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig

/** Speed test: how it works, the user's server, the public secondary target (decision 003, step 2). */
@Composable
internal fun SpeedTestSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.speedTest.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_speed_test), onBack) {
        HowItWorksCard(saved)
        ServerCard(saved, viewModel::save, viewModel::cleartextBlocked)
        PublicTargetCard()
    }
}

@Composable
private fun HowItWorksCard(config: SpeedTestConfig) {
    val downMb = (config.maxDownloadBytes / MB).toInt()
    val upMb = (config.maxUploadBytes / MB).toInt()
    SettingCard(stringResource(R.string.speed_how_title), stringResource(R.string.speed_how_info)) {
        Bullet(pluralStringResource(R.plurals.speed_how_ping, config.pingCount, config.pingCount))
        val connections = pluralStringResource(R.plurals.speed_connections, config.streams, config.streams)
        Bullet(stringResource(R.string.speed_how_transfer, (config.durationMs / 1000).toInt(), connections, downMb, upMb))
        Bullet(stringResource(R.string.speed_how_data, downMb + upMb))
        Bullet(stringResource(R.string.speed_how_stored))
    }
}

@Composable
private fun ServerCard(
    saved: SpeedTestConfig,
    onSave: (String, String, String) -> Unit,
    cleartextBlocked: (List<String>) -> List<String>,
) {
    var base by rememberSaveable { mutableStateOf("") }
    var ping by rememberSaveable(saved) { mutableStateOf(saved.pingUrl) }
    var download by rememberSaveable(saved) { mutableStateOf(saved.downloadUrl) }
    var upload by rememberSaveable(saved) { mutableStateOf(saved.uploadUrl) }
    val changed = remember(saved, ping, download, upload) {
        ping.trim() != saved.pingUrl || download.trim() != saved.downloadUrl || upload.trim() != saved.uploadUrl
    }

    SettingCard(stringResource(R.string.speed_server_title), stringResource(R.string.speed_server_info)) {
        UrlField(base, { base = it }, stringResource(R.string.speed_base_url), null)
        OutlinedButton(
            onClick = {
                SpeedTestConfig.libreSpeed(base).let { ping = it.pingUrl; download = it.downloadUrl; upload = it.uploadUrl }
            },
            enabled = base.trim().startsWith("http://") || base.trim().startsWith("https://"),
        ) { Text(stringResource(R.string.speed_fill)) }
        UrlField(ping, { ping = it }, stringResource(R.string.speed_ping_url), stringResource(R.string.speed_ping_url_info))
        UrlField(download, { download = it }, stringResource(R.string.speed_download_url), stringResource(R.string.speed_download_url_info))
        UrlField(upload, { upload = it }, stringResource(R.string.speed_upload_url), stringResource(R.string.speed_upload_url_info))
        val blocked = remember(ping, download, upload) { cleartextBlocked(listOf(ping, download, upload)) }
        if (blocked.isNotEmpty()) {
            Text(
                stringResource(R.string.speed_cleartext_blocked, blocked.joinToString()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { onSave(ping, download, upload) }, enabled = changed) { Text(stringResource(R.string.speed_save)) }
        Text(
            stringResource(if (saved.isComplete) R.string.speed_saved else R.string.speed_not_saved),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UrlField(value: String, onChange: (String) -> Unit, label: String, help: String?) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

@Composable
private fun PublicTargetCard() {
    SettingCard(stringResource(R.string.speed_public_title), stringResource(R.string.speed_public_info, SpeedTestConfig.CLOUDFLARE)) {}
}

private const val MB = 1024L * 1024
