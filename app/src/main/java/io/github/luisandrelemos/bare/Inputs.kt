package io.github.luisandrelemos.bare

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** A way to the TV's own picture: live TV, or an HDMI or AV input. */
class Input(val label: String, val icon: Int, val intent: Intent)

object Inputs {
    /**
     * Known live TV apps, which also play the inputs; the first installed wins, after the theme's `live_tv_package`.
     * TCL, Google, MediaTek boards (Hisense, Philips…), Sony, Realtek boards, Xiaomi, and AOSP's live channels.
     */
    private val LIVE_TV_APPS = listOf(
        "com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx", "com.realtek.tv",
        "com.mitv.livetv", "com.smartdevice.livetv", "com.android.tv",
    )
    private const val CHANNEL_TYPE = "vnd.android.cursor.item/channel"
    /** The framework names hardware inputs ".../HW<n>"; system apps such as AirPlay also register software "tuners". */
    private val HARDWARE = Regex(".*/HW\\d+$")

    /**
     * Live TV first, then the HDMI ports in order, then AV. Inputs hidden in the TV's Settings > Inputs stay hidden.
     * A TV without the TV input framework (a streaming box) has none, and Home shows no Inputs at all.
     */
    fun load(context: Context, theme: Theme): List<Input> {
        val all = tvInputs(context)
        val inputs = mutableListOf<Input>()
        liveTv(context, theme, all)?.let { inputs += Input(context.getString(R.string.live_tv), R.drawable.ic_tv, it) }

        val ids = all.map { it.id }.toSet()
        val ports = all
            .filter { it.isPassthroughInput && !it.isHidden(context) && (it.parentId == null || it.parentId !in ids) }
            .sortedWith(compareBy<TvInputInfo> { it.type != TvInputInfo.TYPE_HDMI }.thenBy { port(context, it) })
        for (port in ports) {
            val hdmi = port.type == TvInputInfo.TYPE_HDMI
            var name = port(context, port)
            if (hdmi && !name.contains("HDMI", ignoreCase = true)) name = "HDMI $name"
            // An HDMI-CEC device (a console, a soundbar) gets an input of its own on its port: open that one, so the
            // device wakes up too. A name given in Settings > Inputs ("PC") goes first, the port after it.
            val device = all.firstOrNull { it.parentId == port.id && it.isPassthroughInput && !it.isHidden(context) }
            val given = device?.let { custom(context, it) ?: port(context, it) } ?: custom(context, port)
            val label = if (given != null && given != name) "$given · $name" else name
            inputs += Input(label, if (hdmi) R.drawable.ic_hdmi else R.drawable.ic_input, open(context, theme, (device ?: port).id))
        }
        return inputs
    }

    /** The TV's live TV, tuned to its antenna input where it can be: what the remote's TV button opens. */
    fun liveTv(context: Context, theme: Theme): Intent? = liveTv(context, theme, tvInputs(context))

    private fun tvInputs(context: Context): List<TvInputInfo> = try {
        (context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager)?.tvInputList.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun liveTv(context: Context, theme: Theme, all: List<TvInputInfo>): Intent? {
        val pm = context.packageManager
        for (pkg in listOfNotNull(theme.liveTvPackage) + LIVE_TV_APPS) {
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: continue
            // Tune the hardware tuner directly when the live TV app takes it (an analog one is often a dead source).
            val tuner = all
                .filter { it.type == TvInputInfo.TYPE_TUNER && HARDWARE.matches(it.id) && !it.id.contains("analog", ignoreCase = true) }
                .minByOrNull { it.id }
            if (tuner != null) {
                val tuned = view(tuner.id).setPackage(pkg)
                if (tuned.resolveActivity(pm) != null) return tuned
            }
            return launch
        }
        return null
    }

    /** The input's own name, "HDMI 1". */
    private fun port(context: Context, info: TvInputInfo): String =
        info.loadLabel(context)?.toString()?.takeIf { it.isNotBlank() } ?: info.id

    /** The name the owner gave it in Settings > Inputs, if any. */
    private fun custom(context: Context, info: TvInputInfo): String? =
        info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }

    private fun view(inputId: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), CHANNEL_TYPE)

    /** The standard way, or the theme's `input_action` on a TV where nothing answers the standard way. */
    private fun open(context: Context, theme: Theme, inputId: String): Intent {
        val standard = view(inputId)
        val action = theme.inputAction ?: return standard
        if (standard.resolveActivity(context.packageManager) != null) return standard
        return Intent(action).setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), CHANNEL_TYPE)
    }
}
