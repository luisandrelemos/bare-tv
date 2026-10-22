package io.github.luisandrelemos.bare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * The remote's Settings, TV and app buttons from any app. Many remotes send codes only the TV's stock home answered.
 * Reads those keys and nothing else (every other key passes straight through), in its own process, since the system
 * waits for it on every key press. `tools/tv.py` turns it on with the home screen and off with `restore`.
 */
class RemoteKeys : AccessibilityService() {

    private var keys = Keys()
    private var stamp = -1L

    override fun onServiceConnected() {
        refresh()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        refresh()
        val button = keys.of(event.keyCode) ?: return false
        // Act once, on release; swallow the press too, so nothing else half-handles it.
        if (event.action == KeyEvent.ACTION_UP) button.press(this)
        return true
    }

    /** The theme's keys, re-read only when a new theme arrives. */
    private fun refresh() {
        val now = Theme.stamp(this)
        if (now == stamp) return
        stamp = now
        keys = Theme.load(this).keys
        // On some firmware any key-filtering service stops held keys repeating (volume steps once): the theme can
        // switch the filtering off while the service stays on.
        serviceInfo = serviceInfo?.apply {
            flags = if (keys.enabled) flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            else flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}

/** What the remote's buttons do, from the theme's `remote` object. */
data class Keys(
    val enabled: Boolean = true,
    val settings: Set<Int> = DEFAULT_SETTINGS,
    val liveTv: Set<Int> = DEFAULT_LIVE_TV,
    val apps: Map<Int, String> = emptyMap(),
) {
    sealed class Button {
        abstract fun intent(context: Context): Intent?

        object Settings : Button() {
            override fun intent(context: Context) = Intent(android.provider.Settings.ACTION_SETTINGS)
        }

        object LiveTv : Button() {
            override fun intent(context: Context) = Inputs.liveTv(context, Theme.load(context))
        }

        class App(private val packageName: String) : Button() {
            override fun intent(context: Context) = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)
        }

        fun press(context: Context) {
            val intent = intent(context) ?: return
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: RuntimeException) {
                // Nothing to open on this TV: the button stays as quiet as it was.
            }
        }
    }

    fun of(keyCode: Int): Button? = when (keyCode) {
        in apps -> Button.App(apps.getValue(keyCode))
        in settings -> Button.Settings
        in liveTv -> Button.LiveTv
        else -> null
    }

    companion object {
        /** TCL remotes: the gear sends 83 or 4077, TV sends 4001 or 4020. KEYCODE_SETTINGS is the standard code. */
        private val DEFAULT_SETTINGS = setOf(83, 4077, KeyEvent.KEYCODE_SETTINGS)
        private val DEFAULT_LIVE_TV = setOf(4001, 4020)

        /**
         * `"remote": {"enabled": true, "settings": [83], "live_tv": [4001], "apps": {"4062": "com.netflix.ninja"}}`.
         * A key is a number or a name such as `"KEYCODE_BUTTON_3"`. Lists replace the defaults; a bad one keeps them.
         */
        fun from(json: JSONObject?): Keys {
            if (json == null) return Keys()
            val d = Keys()
            val apps = json.optJSONObject("apps")?.let { map ->
                map.keys().asSequence().mapNotNull { key -> code(key)?.let { it to map.optString(key).trim() } }
                    .filter { it.second.isNotEmpty() }.toMap()
            }.orEmpty()
            return Keys(
                enabled = json.optBoolean("enabled", d.enabled),
                settings = codes(json, "settings") ?: d.settings,
                liveTv = codes(json, "live_tv") ?: d.liveTv,
                apps = apps,
            )
        }

        private fun codes(json: JSONObject, key: String): Set<Int>? {
            val array = json.optJSONArray(key) ?: return null
            return (0 until array.length()).mapNotNull { code(array.optString(it)) }.toSet()
        }

        private fun code(value: String): Int? = value.trim().toIntOrNull()
            ?: KeyEvent.keyCodeFromString(value.trim()).takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }
}
