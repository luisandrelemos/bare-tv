package io.github.luisandrelemos.bare

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * The home screen, one page that grows downwards. First: the greeting, clock, date and weather top left; Inputs,
 * Wi-Fi and Settings top right; the favorite apps along the bottom. Down from the favorites the page glides up into
 * the inputs, the forecast and every app; Back or Home glides it back.
 */
class HomeActivity : Activity() {

    /** Everything Home shows, loaded together off the main thread. */
    private class Content(
        val theme: Theme = Theme(),
        val apps: List<App> = emptyList(),
        val inputs: List<Input> = emptyList(),
        val wallpaper: Bitmap? = null,
    )

    private sealed class Focus {
        class Dock(val packageName: String?) : Focus()
        class Grid(val packageName: String) : Focus()
    }

    private var content = Content()
    private var themeStamp = -1L
    private var appsChanged = true
    private var fetchingWeather = false

    private var page: Page? = null
    private var header: Header? = null
    private var weatherCard: WeatherCard? = null
    private var glow: Glow? = null
    private var pills: LinearLayout? = null
    private var dock: LinearLayout? = null
    private var inputsRow: LinearLayout? = null
    private var firstAppRow: LinearLayout? = null
    private var sections: List<View> = emptyList()
    private var gridTiles: List<Tile> = emptyList()

    private var dockPackages: List<String> = emptyList()
    private var lastDockApp: String? = null
    private var lastInput = 0
    private var moving: Tile? = null

    private val handler = Handler(Looper.getMainLooper())
    private val everyMinute = object : Runnable {
        override fun run() {
            header?.updateGreeting()
            refreshWeather()
            handler.postDelayed(this, 60_000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsChanged = true
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(packageReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val stamp = Theme.stamp(this)
        if (stamp != themeStamp || appsChanged) {
            themeStamp = stamp
            appsChanged = false
            reload()
        }
        handler.post(everyMinute)
    }

    override fun onPause() {
        handler.removeCallbacks(everyMinute)
        finishMove()
        super.onPause()
    }

    /** Home pressed while already home: back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishMove()
        backToTop()
    }

    @Deprecated("Still the simplest way to handle Back without AndroidX")
    override fun onBackPressed() {
        if ((page?.scrollY ?: 0) > 0) backToTop()
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val tile = moving
        if (tile != null && event.keyCode in MOVE_KEYS) {
            // The press that started the move is still held: its repeats and release do nothing.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveInDock(tile, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveInDock(tile, 1)
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    finishMove()
                    toggleFavorite(tile.app)
                }
                else -> finishMove()
            }
            return true
        }
        // Any key that isn't for moving around, logged so `tv.py keys listen` can tell what a remote's buttons send.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode !in NAVIGATION_KEYS) {
            Log.i(KEY_LOG, "${event.keyCode} ${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode}")
        }
        // On Home itself the remote's buttons work even before RemoteKeys is switched on.
        content.theme.keys.of(event.keyCode)?.let { button ->
            if (event.action == KeyEvent.ACTION_UP) button.press(this)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (returnTo(event.keyCode)?.requestFocus() == true) return true
            // Nothing above the buttons top right.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && currentFocus?.parent === pills) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Where focus goes back to when it re-enters a row: the app or input you were last on, not whichever happens to
     * sit nearest on screen (from the buttons top right, that was always the last favorite). Null: the usual way.
     */
    private fun returnTo(keyCode: Int): View? {
        val focus = currentFocus ?: return null
        val parent = focus.parent
        val entries = shownSections(content.theme).map {
            when (it) {
                "inputs" -> inputsRow
                "weather" -> weatherCard?.view
                else -> firstAppRow
            }
        }
        val index = entries.indexOfFirst { it != null && (focus === it || parent === it) }
        val lastInputChip = inputsRow?.getChildAt(lastInput)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                parent === pills -> lastDockTile()
                parent === dock && entries.firstOrNull() === inputsRow -> lastInputChip
                else -> null
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                index == 0 -> lastDockTile()
                index > 0 && entries[index - 1] === inputsRow -> lastInputChip
                else -> null
            }
            else -> null
        }
    }

    /** The sections below the favorites, in the theme's order, leaving out what this TV or theme doesn't have. */
    private fun shownSections(theme: Theme) = theme.sections.filter {
        when (it) {
            "inputs" -> content.inputs.isNotEmpty()
            "weather" -> theme.weather != null
            else -> true
        }
    }


    private fun reload() {
        Thread {
            val theme = if (CrashGuard.safeMode(this)) Theme() else Theme.load(this)
            // Art as large as the largest tile (a dock tile, or one in All apps), so no tile ever scales it up.
            val artWidth = maxOf(dp(DOCK_TILE_MAX), gridTileWidth(theme))
            val loaded = Content(
                theme = theme,
                apps = Apps.load(this, artWidth, artWidth * 9 / 16, theme.appLabels),
                inputs = Inputs.load(this, theme),
                wallpaper = theme.wallpaper?.let { Theme.decodeWallpaper(this, it) },
            )
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                content = loaded
                show(Focus.Dock(lastDockApp))
            }
        }.start()
    }

    private fun refreshWeather() {
        val place = content.theme.weather ?: return
        if (fetchingWeather || !Weather.stale(this, place)) return
        fetchingWeather = true
        Thread {
            val fresh = Weather.fetch(this, place)
            runOnUiThread {
                fetchingWeather = false
                if (fresh && !isDestroyed) showWeather()
            }
        }.start()
    }

    private fun showWeather() {
        val place = content.theme.weather ?: return
        val forecast = Weather.cached(this, place)
        header?.showWeather(forecast, place)
        weatherCard?.show(forecast)
    }


    private fun show(focus: Focus) {
        val theme = content.theme
        val screenHeight = resources.displayMetrics.heightPixels
        moving = null
        val dockApps = Apps.dock(content.apps, theme, Favorites.get(this))
        dockPackages = dockApps.map { it.packageName }

        // Every full-screen layer costs the GPU a full screen of pixels per frame: the window paints the background once.
        val root = FrameLayout(this)
        val lit = theme.glow && content.wallpaper == null
        window.setBackgroundDrawable(if (lit) ColorDrawable(theme.background)
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.backgroundTop, theme.background)))
        glow = if (lit) Glow(this).also { root.addView(it.view, fill()) } else null
        // Dims a wallpaper as the page glides up, so the sections below stay readable.
        val shade = content.wallpaper?.let {
            root.addView(ImageView(this).apply {
                setImageBitmap(it)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, fill())
            View(this).apply {
                setBackgroundColor(theme.background)
                alpha = 0f
            }.also { shade -> root.addView(shade, fill()) }
        }
        val light = glow?.view
        // Darkens the bottom so the favorites read on any background.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(withAlpha(theme.background, 0xE0), withAlpha(theme.background, 0)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, screenHeight / 2, Gravity.BOTTOM))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(72))
        }
        val hero = hero(theme, dockApps)
        column.addView(hero, linearParams(MATCH_PARENT, screenHeight))
        inputsRow = null
        weatherCard = null
        sections = shownSections(theme).map {
            when (it) {
                "inputs" -> inputsSection(theme)
                "weather" -> weatherSection(theme, theme.weather!!)
                else -> appsSection(theme)
            }
        }
        sections.forEach { column.addView(it) }

        page = Page(this).apply {
            addView(column)
            setOnScrollChangeListener { _, _, y, _, _ ->
                val progress = (y / (screenHeight * 0.55f)).coerceIn(0f, 1f)
                hero.alpha = 1f - 0.7f * progress
                light?.alpha = 1f - 0.55f * progress
                shade?.alpha = 0.55f * progress
            }
        }.also { root.addView(it, fill()) }
        setContentView(root)

        showWeather()
        refreshWeather()

        val dockTiles = dock?.children.orEmpty().filterIsInstance<Tile>()
        val target = when (focus) {
            is Focus.Grid -> gridTiles.firstOrNull { it.app.packageName == focus.packageName }
            is Focus.Dock -> dockTiles.firstOrNull { it.app.packageName == focus.packageName } ?: dockTiles.firstOrNull()
        } ?: pills?.getChildAt(0) ?: firstAppRow?.getChildAt(0)
        target?.requestFocus()
    }

    /** The first screen: header top left, buttons top right, favorites along the bottom. */
    private fun hero(theme: Theme, dockApps: List<App>) = Faded(this).apply {
        clipChildren = false
        header = Header(context, theme).also {
            addView(it.view, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(SIDE)
                topMargin = dp(60)
            })
        }
        addView(pills(theme), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(SIDE)
            topMargin = dp(32)
        })
        addView(favorites(theme, dockApps), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(SIDE)
            rightMargin = dp(SIDE)
            bottomMargin = dp(28)
        })
    }

    /** The buttons top right, in the theme's order. Inputs only where the page has its section to go to. */
    private fun pills(theme: Theme) = LinearLayout(this).apply {
        clipChildren = false
        for (button in theme.buttons) when (button) {
            "inputs" -> if ("inputs" in shownSections(theme)) {
                addView(pill(theme, R.drawable.ic_input, R.string.inputs) { inputsRow?.getChildAt(lastInput)?.requestFocus() })
            }
            "wifi" -> addView(pill(theme, R.drawable.ic_wifi, R.string.wifi) { open(Intent(Settings.ACTION_WIFI_SETTINGS), R.string.wifi) })
            "settings" -> addView(pill(theme, R.drawable.ic_settings, R.string.settings) { open(Intent(Settings.ACTION_SETTINGS), R.string.settings) })
        }
        children.drop(1).forEach { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(12) }
        pinEnds()
        pills = this
    }

    /** A capsule with an icon and its name. */
    private fun pill(theme: Theme, icon: Int, name: Int, onClick: () -> Unit): View {
        val glass = glass(theme, 24)
        val image = icon(icon, theme.text)
        val label = label(getString(name), 15f, theme.text, "sans-serif-medium")
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass
            isFocusable = true
            isClickable = true
            setPadding(dp(16), 0, dp(20), 0)
            addView(image, linearParams(dp(20), dp(20)))
            addView(label, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
            layoutParams = linearParams(WRAP_CONTENT, dp(44))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, focused -> showGlassFocus(focused, glass, theme, 1.06f, image, label) }
        }
    }

    private fun favorites(theme: Theme, apps: List<App>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(belowHint(theme), linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })

        val row = LinearLayout(context).apply { clipChildren = false }
        dock = row
        if (apps.isEmpty()) {
            addView(label(getString(R.string.dock_empty), 16f, theme.dim))
            return@apply
        }
        val width = dockTileWidth(apps.size)
        apps.forEachIndexed { i, app ->
            val tile = Tile(context, app, theme, width, nameOnFocus = true)
            tile.onFocus = {
                lastDockApp = app.packageName
                page?.glideTo(0)
                highlight(null)
                glow?.show(app.color)
            }
            tile.onLongPress = { startMove(tile) }
            row.addView(tile, linearParams(width, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(DOCK_GAP) })
        }
        row.pinEnds()
        addView(row)
    }

    /** "⌄ INPUTS · WEATHER · ALL APPS": what's below the favorites. */
    private fun belowHint(theme: Theme) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(R.drawable.ic_chevron_down, theme.dim), linearParams(dp(16), dp(16)))
        val below = shownSections(theme).map {
            getString(when (it) {
                "inputs" -> R.string.inputs
                "weather" -> R.string.weather
                else -> R.string.all_apps
            })
        }
        addView(label(below.joinToString("  ·  "), 12f, theme.dim, "sans-serif-medium").apply {
            letterSpacing = 0.16f
            isAllCaps = true
        }, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    }

    private fun dockTileWidth(count: Int): Int {
        val available = resources.displayMetrics.widthPixels - 2 * dp(SIDE)
        return minOf(dp(DOCK_TILE_MAX), (available - dp(DOCK_GAP) * (count - 1)) / count)
    }

    private fun gridTileWidth(theme: Theme) =
        (resources.displayMetrics.widthPixels - 2 * dp(SIDE) - (theme.columns - 1) * dp(GRID_GAP)) / theme.columns

    private fun section(theme: Theme, title: String, aside: String? = null) = Section(this).apply {
        clipChildren = false
        clipToPadding = false
        setPadding(dp(SIDE), dp(36), dp(SIDE), 0)
        val heading = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        heading.addView(label(title, 22f, theme.text, "sans-serif-light"), linearParams(0, WRAP_CONTENT, 1f))
        if (aside != null) heading.addView(label(aside, 12f, theme.dim))
        addView(heading, linearParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
    }

    private fun inputsSection(theme: Theme): View {
        val section = section(theme, getString(R.string.inputs))
        val row = LinearLayout(this).apply {
            clipChildren = false
            // The padding must not clip either: a focused chip grows past it.
            clipToPadding = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        content.inputs.forEachIndexed { i, input ->
            row.addView(inputChip(theme, input, section, i), linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(14) })
        }
        row.pinEnds()
        inputsRow = row
        section.addView(row, linearParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = -dp(6) })
        return section
    }

    private fun inputChip(theme: Theme, input: Input, section: View, index: Int): View {
        val glass = glass(theme, 16)
        val image = icon(input.icon, theme.text)
        val label = label(input.label, 17f, theme.text)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass
            isFocusable = true
            isClickable = true
            setPadding(dp(20), dp(14), dp(24), dp(14))
            addView(image, linearParams(dp(22), dp(22)))
            addView(label, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
            setOnClickListener { open(input.intent, input.label) }
            setOnFocusChangeListener { _, focused ->
                showGlassFocus(focused, glass, theme, 1.05f, image, label)
                if (focused) {
                    lastInput = index
                    center(section)
                    highlight(section)
                }
            }
        }
    }

    private fun weatherSection(theme: Theme, place: Place): View {
        val title = if (place.city.isNotEmpty()) getString(R.string.weather_in, place.city) else getString(R.string.weather)
        val section = section(theme, title, getString(R.string.weather_source))
        val card = WeatherCard(this, theme)
        card.view.setOnFocusChangeListener { _, focused ->
            card.showFocus(focused)
            if (focused) {
                center(section)
                highlight(section)
            }
        }
        weatherCard = card
        section.addView(card.view, linearParams(MATCH_PARENT, WRAP_CONTENT))
        return section
    }

    private fun appsSection(theme: Theme): View {
        val section = section(theme, getString(R.string.all_apps), getString(R.string.favorites_hint))
        val gap = dp(GRID_GAP)
        val width = gridTileWidth(theme)
        val tiles = mutableListOf<Tile>()
        firstAppRow = null
        Apps.arrange(content.apps, theme).chunked(theme.columns).forEachIndexed { rowIndex, rowApps ->
            val row = LinearLayout(this).apply { clipChildren = false }
            for (app in rowApps) {
                val tile = Tile(this, app, theme, width, favorite = app.packageName in dockPackages)
                tile.onLongPress = { toggleFavorite(app) }
                tile.onFocus = {
                    // The first row brings the title along; further down, each row glides to the middle.
                    if (rowIndex == 0) center(section, until = row) else center(row)
                    highlight(section)
                    glow?.show(app.color)
                }
                row.addView(tile, linearParams(width, WRAP_CONTENT).apply { if (row.childCount > 0) marginStart = gap })
                tiles += tile
            }
            row.pinEnds()
            if (rowIndex == 0) firstAppRow = row
            section.addView(row, linearParams(MATCH_PARENT, WRAP_CONTENT).apply { if (rowIndex > 0) topMargin = gap })
        }
        if (tiles.isEmpty()) section.addView(label(getString(R.string.no_apps), 16f, theme.dim))
        gridTiles = tiles
        return section
    }


    private fun toggleFavorite(app: App) {
        val favorites = dockPackages.toMutableList()
        val adding = app.packageName !in favorites
        if (adding && favorites.size >= Favorites.MAX) {
            toast(getString(R.string.dock_full))
            return
        }
        if (adding) favorites += app.packageName else favorites -= app.packageName
        Favorites.set(this, favorites)
        themeStamp = Theme.stamp(this) // Already shown: no reload for this change.
        toast(getString(if (adding) R.string.added_to_dock else R.string.removed_from_dock, app.label))
        show(if (currentFocus?.parent === dock) Focus.Dock(null) else Focus.Grid(app.packageName))
    }

    private fun startMove(tile: Tile) {
        moving = tile
        tile.setMoving(true)
    }

    /** Swaps the moving tile with its neighbor. The neighbor moves, so the focused tile never leaves the screen. */
    private fun moveInDock(tile: Tile, delta: Int) {
        val row = dock ?: return
        val index = row.indexOfChild(tile)
        val neighbor = row.getChildAt(index + delta) ?: return
        row.removeView(neighbor)
        row.addView(neighbor, index)
        row.children.forEachIndexed { i, child ->
            // Both: once laid out, a changed marginStart alone isn't resolved into the left margin again.
            (child.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = if (i > 0) dp(DOCK_GAP) else 0
                leftMargin = marginStart
            }
        }
        row.pinEnds()
        row.requestLayout()
    }

    private fun finishMove() {
        val tile = moving ?: return
        moving = null
        tile.setMoving(false)
        dockPackages = dock?.children.orEmpty().filterIsInstance<Tile>().map { it.app.packageName }
        Favorites.set(this, dockPackages)
        themeStamp = Theme.stamp(this)
    }


    private fun lastDockTile(): View? {
        val tiles = dock?.children.orEmpty().filterIsInstance<Tile>()
        return tiles.firstOrNull { it.app.packageName == lastDockApp } ?: tiles.firstOrNull()
    }

    private fun backToTop() {
        lastDockTile()?.requestFocus()
        page?.glideTo(0)
    }

    /** Glides the page so [view] (down to [until], if given) sits in the middle of the screen, as far as it can. */
    private fun center(view: View, until: View? = null) {
        val page = page ?: return
        val column = page.getChildAt(0) as? ViewGroup ?: return
        if (page.height == 0 || view.height == 0) {
            page.post { center(view, until) }
            return
        }
        val area = Rect().also { view.getDrawingRect(it) }
        column.offsetDescendantRectToMyCoords(view, area)
        if (until != null) {
            val end = Rect().also { until.getDrawingRect(it) }
            column.offsetDescendantRectToMyCoords(until, end)
            area.bottom = end.bottom
        }
        page.glideTo(area.centerY() - page.height / 2)
    }

    /** The section with focus at full strength, the others quieter; null when focus is on the first screen. */
    private fun highlight(section: View?) {
        for (other in sections) {
            val alpha = if (section == null || other === section) 1f else 0.4f
            if (other.alpha != alpha) other.animate().alpha(alpha).setDuration(300).start()
        }
    }


    private fun open(intent: Intent, name: Int) = open(intent, getString(name))

    private fun open(intent: Intent, name: String) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            toast(getString(R.string.cannot_open, name))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun fill() = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    companion object {
        private const val SIDE = 64
        private const val DOCK_GAP = 18
        private const val DOCK_TILE_MAX = 168
        private const val GRID_GAP = 20
        private const val KEY_LOG = "BareKeys"
        private val MOVE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
        )
        private val NAVIGATION_KEYS = MOVE_KEYS + KeyEvent.KEYCODE_HOME
    }
}
