package id.my.pamslab.netlogger.core.data

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speed test server URLs, set in Settings. Empty until the user enters a server: nothing points at a particular
 * server by default. The public Cloudflare test ([SpeedTestConfig.cloudflare]) needs no setting: Live always offers it
 * as its own button, never as the default (2026-10-17; the earlier on/off switch hid it behind a grey link).
 */
interface SpeedTestSettings {
    val config: StateFlow<SpeedTestConfig>

    fun save(pingUrl: String, downloadUrl: String, uploadUrl: String)
}

/** SharedPreferences (no extra dependency for three strings). */
@Singleton
class SharedPreferencesSpeedTestSettings @Inject constructor(
    @ApplicationContext context: Context,
) : SpeedTestSettings {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    override val config: StateFlow<SpeedTestConfig> = _config.asStateFlow()

    @SuppressLint("UseKtx") // core-ktx is not a dependency of core:data; not worth one for a single edit.
    override fun save(pingUrl: String, downloadUrl: String, uploadUrl: String) {
        prefs.edit()
            .putString(KEY_PING, pingUrl.trim())
            .putString(KEY_DOWNLOAD, downloadUrl.trim())
            .putString(KEY_UPLOAD, uploadUrl.trim())
            .apply()
        _config.value = read()
    }

    private fun read() = SpeedTestConfig(
        pingUrl = prefs.getString(KEY_PING, null).orEmpty(),
        downloadUrl = prefs.getString(KEY_DOWNLOAD, null).orEmpty(),
        uploadUrl = prefs.getString(KEY_UPLOAD, null).orEmpty(),
    )

    private companion object {
        const val FILE = "speedtest"
        const val KEY_PING = "pingUrl"
        const val KEY_DOWNLOAD = "downloadUrl"
        const val KEY_UPLOAD = "uploadUrl"
    }
}
