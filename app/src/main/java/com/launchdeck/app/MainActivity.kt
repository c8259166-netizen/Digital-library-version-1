package com.launchdeck.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val isVr: Boolean,
    val androidGame: Boolean
)

enum class Kind { ICON, BANNER }

/** Opens the launcher a few seconds after the headset starts, if the user turned that on in Tools. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val prefs = context.getSharedPreferences("digitallibrary", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", false)) return
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(6000)
                val i = Intent(context, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Downloads store pictures and names from the MetaMetadata project (the same source
 * Lightning Launcher uses), keeps them on disk, and hands out small bitmaps.
 * All network and decoding work happens on background threads.
 */
class ArtStore(context: Context, private val onUpdate: () -> Unit) {
    private val base = "https://raw.githubusercontent.com/threethan/MetaMetadata/main/data/common/"
    private val week = 7L * 24 * 3600 * 1000
    private val dir = File(context.filesDir, "art").apply { mkdirs() }
    private val metaPool = Executors.newFixedThreadPool(3)
    private val imgPool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val names = ConcurrentHashMap<String, String>()
    private val metaMemo = ConcurrentHashMap<String, JSONObject>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val notifyPosted = AtomicBoolean(false)

    private val found = AtomicInteger(0)
    private val notInStore = AtomicInteger(0)
    private val netErrors = AtomicInteger(0)
    @Volatile private var lastError = ""

    fun nameOf(pkg: String): String? = names[pkg]

    /** The store category (for example Games or Apps) if the store data has one. */
    fun categoryOf(pkg: String): String? {
        val o = metaMemo[pkg] ?: return null
        val c = str(o, "category")
        return if (c.isBlank()) null else c
    }

    fun stats(): String {
        val s = "Store data: ${found.get()} found, ${notInStore.get()} not in the store list, " +
            "${netErrors.get()} network errors"
        return if (lastError.isNotEmpty()) "$s\nLast error: $lastError" else s
    }

    /** Returns the picture if it is ready, otherwise starts loading it and returns null. */
    fun get(pkg: String, kind: Kind): Bitmap? {
        val key = kind.name + ":" + pkg
        val hit = cache.get(key)
        if (hit != null) return hit
        if (failed.contains(key)) return null
        if (pending.add(key)) {
            imgPool.execute {
                try {
                    load(pkg, kind, key)
                } catch (e: Throwable) {
                    lastError = e.javaClass.simpleName
                    failed.add(key)
                } finally {
                    pending.remove(key)
                }
            }
        }
        return null
    }

    /** Fetches the small data file for every app so names are right everywhere. */
    fun prefetchNames(pkgs: List<String>) {
        for (p in pkgs) {
            metaPool.execute {
                try {
                    if (meta(p) != null) postUpdate()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Forget everything and download again. */
    fun refresh(pkgs: List<String>) {
        cache.evictAll()
        failed.clear()
        missing.clear()
        metaMemo.clear()
        names.clear()
        dir.listFiles()?.forEach { it.delete() }
        found.set(0)
        notInStore.set(0)
        netErrors.set(0)
        lastError = ""
        prefetchNames(pkgs)
        postUpdate()
    }

    private fun postUpdate() {
        if (notifyPosted.compareAndSet(false, true)) {
            ui.postDelayed({
                notifyPosted.set(false)
                onUpdate()
            }, 300)
        }
    }

    private fun load(pkg: String, kind: Kind, key: String) {
        val file = File(dir, kind.name.lowercase() + "_" + pkg + ".img")
        if (!file.exists() || file.length() == 0L) {
            val m = meta(pkg)
            if (m == null) {
                failed.add(key)
                return
            }
            var ok = false
            for (u in urlsFor(m, kind)) {
                if (download(u, file)) {
                    ok = true
                    break
                }
            }
            if (!ok) {
                failed.add(key)
                return
            }
        }
        val bmp = decode(file, if (kind == Kind.ICON) 256 else 480)
        if (bmp == null) {
            file.delete()
            failed.add(key)
            return
        }
        cache.put(key, bmp)
        postUpdate()
    }

    private fun urlsFor(o: JSONObject, kind: Kind): List<String> {
        val keys = if (kind == Kind.ICON) listOf("icon", "square", "landscape")
        else listOf("landscape", "hero", "square")
        return keys.map { str(o, it) }.filter { it.startsWith("http") }
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    private fun meta(pkg: String): JSONObject? {
        val lock = locks.computeIfAbsent(pkg) { Any() }
        return synchronized(lock) { metaLocked(pkg) }
    }

    private fun remember(pkg: String, o: JSONObject) {
        metaMemo[pkg] = o
        val n = str(o, "name")
        if (n.isNotBlank()) names[pkg] = n
        found.incrementAndGet()
    }

    private fun metaLocked(pkg: String): JSONObject? {
        val memo = metaMemo[pkg]
        if (memo != null) return memo
        if (missing.contains(pkg)) return null

        val f = File(dir, "meta_$pkg.json")
        var stale: JSONObject? = null
        if (f.exists()) {
            val text = try { f.readText() } catch (_: Exception) { "" }
            val fresh = System.currentTimeMillis() - f.lastModified() < week
            if (text == "{}") {
                if (fresh) {
                    missing.add(pkg)
                    notInStore.incrementAndGet()
                    return null
                }
            } else if (text.isNotEmpty()) {
                val o = try { JSONObject(text) } catch (_: Exception) { null }
                if (o != null) {
                    if (fresh) {
                        remember(pkg, o)
                        return o
                    }
                    stale = o
                }
            }
        }

        try {
            val conn = URL(base + pkg + ".json").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "DigitalLibrary")
            val code = conn.responseCode
            if (code == 404) {
                conn.disconnect()
                f.writeText("{}")
                missing.add(pkg)
                notInStore.incrementAndGet()
                return null
            }
            if (code != 200) {
                conn.disconnect()
                netErrors.incrementAndGet()
                lastError = "HTTP $code"
                if (stale != null) remember(pkg, stale)
                return stale
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(text)
            f.writeText(text)
            remember(pkg, o)
            return o
        } catch (e: Exception) {
            netErrors.incrementAndGet()
            lastError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")
            if (stale != null) remember(pkg, stale)
            return stale
        }
    }

    private fun download(url: String, dest: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "DigitalLibrary")
            if (conn.responseCode != 200) {
                conn.disconnect()
                false
            } else {
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                conn.disconnect()
                tmp.renameTo(dest)
            }
        } catch (e: Exception) {
            netErrors.incrementAndGet()
            lastError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")
            false
        }
    }

    private fun decode(file: File, targetW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)
    }
}

/** Decorative background: soft white and blue shapes on black. */
class ShapesView(context: Context) : View(context) {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val blue = Color.parseColor("#2F6BFF")

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = blue; fill.alpha = 38
        c.drawCircle(w * 0.90f, h * 0.15f, h * 0.30f, fill)
        fill.color = Color.WHITE; fill.alpha = 14
        c.drawCircle(w * 0.08f, h * 0.85f, h * 0.26f, fill)
        stroke.color = Color.WHITE; stroke.alpha = 40
        c.drawCircle(w * 0.55f, h * 0.95f, h * 0.18f, stroke)
        stroke.color = blue; stroke.alpha = 90
        c.drawCircle(w * 0.25f, h * 0.10f, h * 0.10f, stroke)
        fill.color = Color.WHITE; fill.alpha = 12
        c.save(); c.rotate(20f, w * 0.72f, h * 0.70f)
        c.drawRect(w * 0.66f, h * 0.60f, w * 0.78f, h * 0.84f, fill)
        c.restore()
        val tri = android.graphics.Path().apply {
            moveTo(w * 0.40f, h * 0.55f); lineTo(w * 0.47f, h * 0.72f); lineTo(w * 0.33f, h * 0.72f); close()
        }
        fill.color = blue; fill.alpha = 30
        c.drawPath(tri, fill)
        stroke.color = Color.WHITE; stroke.alpha = 35
        c.drawRect(w * 0.92f, h * 0.55f, w * 0.97f, h * 0.65f, stroke)
    }
}

class MainActivity : Activity() {

    private val bg = Color.parseColor("#000000")
    private val panel = Color.parseColor("#1C1C1E")
    private val accent = Color.parseColor("#2F6BFF")

    private var allApps: List<AppEntry> = emptyList()
    private lateinit var screen: FrameLayout
    private lateinit var content: FrameLayout
    private val tabButtons = mutableListOf<Button>()
    private val iconCache = HashMap<String, Drawable?>()
    private val handler = Handler(Looper.getMainLooper())

    // Store pictures
    private lateinit var art: ArtStore
    private var style = Kind.ICON
    private var gridRefresh: (() -> Unit)? = null
    private var artStatsView: TextView? = null

    // Wireless debugging state
    private var connectPort: Int? = null
    private var status = "Not connected"
    private var statusView: TextView? = null

    // Commands that open the real Android settings (run through Wireless debugging).
    private val cmdHome = listOf(
        "am start -n com.android.settings/.homepage.SettingsHomepageActivity",
        "am start -n com.android.settings/.Settings"
    )
    private val cmdApps = listOf(
        "am start -n com.android.settings/.UsageStatsActivity",
        "am start -a android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
        "am start -n 'com.android.settings/com.android.settings.Settings\$ManageApplicationsActivity'"
    )
    private val cmdDev = listOf(
        "am start -n 'com.android.settings/com.android.settings.Settings\$DevelopmentSettingsDashboardActivity'",
        "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
    )
    private val cmdWireless = listOf(
        "am start -n 'com.android.settings/com.android.settings.Settings\$WirelessDebuggingActivity'",
        "am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.development.WirelessDebuggingFragment"
    )

    private fun prefs() = getSharedPreferences("digitallibrary", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allApps = loadApps()

        style = if (prefs().getString("style", "icon") == "banner") Kind.BANNER else Kind.ICON
        art = ArtStore(this) {
            gridRefresh?.invoke()
            artStatsView?.text = art.stats()
        }
        art.prefetchNames(allApps.map { it.pkg })

        val frame = FrameLayout(this).apply { setBackgroundColor(bg) }
        frame.addView(ShapesView(this), FrameLayout.LayoutParams(-1, -1))
        screen = FrameLayout(this)
        frame.addView(screen, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)

        if (prefs().getBoolean("paired", false)) {
            // Already paired once: go straight in and reconnect by itself.
            connect(3)
            showLibrary()
        } else {
            showPairing()
        }
    }

    // ---------- Game or app ----------

    private fun isGame(e: AppEntry): Boolean {
        when (prefs().getString("cat_" + e.pkg, null)) {
            "game" -> return true
            "app" -> return false
        }
        val cat = art.categoryOf(e.pkg)?.lowercase()
        if (cat != null) {
            if (cat.contains("game")) return true
            if (cat.contains("app")) return false
        }
        return e.isVr || e.androidGame
    }

    private fun moveDialog(e: AppEntry) {
        val options = arrayOf(
            "Move to Games",
            "Move to Apps",
            "Back to automatic",
            "App info (Android settings)"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(labelOf(e))
            .setItems(options) { _, which ->
                if (which == 3) {
                    openSettings(
                        "app info",
                        listOf("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${e.pkg}"),
                        listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))
                    )
                } else {
                    val ed = prefs().edit()
                    when (which) {
                        0 -> ed.putString("cat_" + e.pkg, "game")
                        1 -> ed.putString("cat_" + e.pkg, "app")
                        else -> ed.remove("cat_" + e.pkg)
                    }
                    ed.apply()
                    gridRefresh?.invoke()
                }
            }
            .show()
    }

    // ---------- Pairing screen ----------

    private fun showPairing() {
        screen.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(120), dp(32), dp(120), dp(32))
        }
        col.addView(TextView(this).apply {
            text = "Digital Library"
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
        })
        col.addView(heading("Connect Wireless debugging (only needed once)"))
        col.addView(note(
            "1. Turn on Wireless debugging in your headset's developer settings.\n" +
            "2. Choose to pair a device with a pairing code.\n" +
            "3. Type the 6 digit code and the port shown there below, then press Pair.\n" +
            "Keep the pairing screen open next to this window while you press Pair."
        ))

        val codeField = EditText(this).apply {
            hint = "Pairing code"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 20f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = round(panel, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val portField = EditText(this).apply {
            hint = "Pairing port"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 20f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = round(panel, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(codeField, LinearLayout.LayoutParams(dp(360), dp(60)).apply { topMargin = dp(8) })
        col.addView(portField, LinearLayout.LayoutParams(dp(360), dp(60)).apply { topMargin = dp(12) })

        val statusText = TextView(this).apply {
            text = status
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        }
        statusView = statusText
        col.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pairBtn = bigButton("Pair", accent) {
            val code = codeField.text.toString().trim()
            val port = portField.text.toString().trim().toIntOrNull()
            if (code.length != 6 || port == null) {
                setStatus("Enter the 6 digit code and the pairing port")
            } else {
                setStatus("Pairing...")
                thread {
                    val ok = try {
                        runBlocking { Kadb.pair("127.0.0.1", port, code) }
                        true
                    } catch (e: Throwable) {
                        false
                    }
                    runOnUiThread {
                        if (ok) {
                            prefs().edit().putBoolean("paired", true).apply()
                            setStatus("Paired. Looking for the connection...")
                            connect(3)
                            showLibrary()
                        } else {
                            setStatus("Pairing failed. Check the code and port, and keep the pairing screen open.")
                        }
                    }
                }
            }
        }
        val skipBtn = bigButton("Skip", panel) {
            connect()
            showLibrary()
        }
        row.addView(pairBtn, LinearLayout.LayoutParams(dp(160), dp(60)).apply { setMargins(0, 0, dp(12), 0) })
        row.addView(skipBtn, LinearLayout.LayoutParams(dp(160), dp(60)))
        col.addView(row)

        screen.addView(ScrollView(this).apply { addView(col) })
    }

    // ---------- Library screen ----------

    private fun showLibrary() {
        screen.removeAllViews()
        tabButtons.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Digital Library"
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(10))
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = listOf("Everything", "Apps", "Games", "Tools")
        names.forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 18f
                setTextColor(Color.WHITE)
                setOnClickListener { showTab(i) }
            }
            tabButtons.add(b)
            bar.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(bar)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        screen.addView(root, FrameLayout.LayoutParams(-1, -1))
        showTab(0)
    }

    private fun showTab(index: Int) {
        tabButtons.forEachIndexed { i, b -> b.background = round(if (i == index) accent else panel, 14) }
        content.removeAllViews()
        statusView = null
        gridRefresh = null
        artStatsView = null
        when (index) {
            0 -> content.addView(appGrid { true })
            1 -> content.addView(appGrid { !isGame(it) })
            2 -> content.addView(appGrid { isGame(it) })
            3 -> content.addView(toolsPage())
        }
    }

    // ---------- App list with search bar ----------

    private fun labelOf(e: AppEntry): String = art.nameOf(e.pkg) ?: e.label

    private fun appGrid(filter: (AppEntry) -> Boolean): View {
        var shown: List<AppEntry> = emptyList()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val search = EditText(this).apply {
            hint = "Search"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT
            background = round(panel, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(search, LinearLayout.LayoutParams(-1, dp(56)))

        fun compute() {
            val q = search.text.toString().trim().lowercase()
            shown = allApps.filter(filter).filter {
                q.isEmpty() || labelOf(it).lowercase().contains(q) || it.label.lowercase().contains(q)
            }
        }
        compute()

        val grid = GridView(this).apply {
            numColumns = if (style == Kind.BANNER) 4 else 6
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val e = shown[p]
                return if (style == Kind.BANNER) bannerTile(e) else iconTile(e)
            }
        }
        grid.adapter = adapter
        gridRefresh = {
            compute()
            adapter.notifyDataSetChanged()
        }
        grid.setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            moveDialog(shown[pos])
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                gridRefresh?.invoke()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        return col
    }

    private fun iconTile(e: AppEntry): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = round(panel, 18)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val bmp = art.get(e.pkg, Kind.ICON)
        val pic: View = if (bmp != null) {
            ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipRound(this, 18)
            }
        } else {
            localPicture(e)
        }
        tile.addView(pic, LinearLayout.LayoutParams(dp(88), dp(88)))
        tile.addView(TextView(this).apply {
            text = labelOf(e)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, dp(6), 0, 0)
        })
        return tile
    }

    private fun bannerTile(e: AppEntry): View {
        val tile = FrameLayout(this).apply { background = round(panel, 18) }
        val bmp = art.get(e.pkg, Kind.BANNER)
        if (bmp != null) {
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tile.addView(img, FrameLayout.LayoutParams(-1, -1))
        } else {
            tile.addView(localPicture(e), FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }
        tile.addView(TextView(this).apply {
            text = labelOf(e)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        clipRound(tile, 18)
        tile.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
        return tile
    }

    /** The picture used when there is no store picture: the app's own icon, or a letter tile. */
    private fun localPicture(e: AppEntry): View {
        val icon = iconFor(e)
        return if (icon != null) {
            ImageView(this).apply { setImageDrawable(icon) }
        } else {
            letterTile(e)
        }
    }

    private fun clipRound(v: View, radiusDp: Int) {
        val r = dp(radiusDp).toFloat()
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }

    private fun launch(e: AppEntry) {
        val i = packageManager.getLaunchIntentForPackage(e.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(e.component)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (ex: Exception) {
            Toast.makeText(this, "Couldn't open ${labelOf(e)}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Local icons (backup) ----------

    private fun toBitmap(source: Drawable): Bitmap {
        val size = 48
        val d = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }

    /** True when the picture is (almost) fully see-through, like many Meta system app icons. */
    private fun isBlank(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var visible = 0
        for (p in px) {
            if (Color.alpha(p) > 40) visible++
        }
        return visible < px.size * 0.04
    }

    private fun iconFor(e: AppEntry): Drawable? {
        if (iconCache.containsKey(e.pkg)) return iconCache[e.pkg]
        val pm = packageManager
        val defaultBmp = try { toBitmap(pm.defaultActivityIcon) } catch (_: Exception) { null }
        var result: Drawable? = null
        val tries = listOf<() -> Drawable?>(
            { pm.getActivityIcon(e.component) },
            { pm.getApplicationIcon(e.pkg) }
        )
        for (t in tries) {
            val d = try { t() } catch (_: Exception) { null }
            if (d == null) continue
            val bmp = try { toBitmap(d) } catch (_: Exception) { null }
            if (bmp == null) continue
            if (isBlank(bmp)) continue
            if (defaultBmp != null && bmp.sameAs(defaultBmp)) continue
            result = d
            break
        }
        iconCache[e.pkg] = result
        return result
    }

    private fun letterTile(e: AppEntry): View {
        val hue = (Math.abs(e.pkg.hashCode()) % 360).toFloat()
        val color = Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.45f))
        return FrameLayout(this).apply {
            background = round(color, 16)
            addView(TextView(this@MainActivity).apply {
                text = labelOf(e).firstOrNull()?.uppercase() ?: "?"
                setTextColor(Color.WHITE)
                textSize = 30f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    // ---------- Tools page ----------

    private fun setStyle(k: Kind) {
        style = k
        prefs().edit().putString("style", if (k == Kind.BANNER) "banner" else "icon").apply()
        showTab(3)
    }

    private fun tryOpen(what: String, vararg intents: Intent?) {
        for (i in intents) {
            if (i == null) continue
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                Toast.makeText(this, "Opening $what...", Toast.LENGTH_SHORT).show()
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            this,
            "Couldn't open $what on this headset.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun comp(cls: String): Intent = Intent().setClassName("com.android.settings", cls)

    /**
     * Opens a screen of the real Android settings. When Wireless debugging is connected the
     * command is sent through it (like from a PC). Otherwise it tries directly, which newer
     * headset software may redirect to Meta Horizon settings.
     */
    private fun openSettings(what: String, commands: List<String>, direct: List<Intent>) {
        val port = connectPort
        if (port == null) {
            Toast.makeText(
                this,
                "Not connected to Wireless debugging yet, trying directly. Meta may send this to Horizon settings instead.",
                Toast.LENGTH_LONG
            ).show()
            tryOpen(what, *direct.toTypedArray())
            return
        }
        thread {
            var ok = false
            try {
                Kadb.create("127.0.0.1", port).use { k ->
                    for (c in commands) {
                        val r = k.shell(c)
                        if (r.exitCode == 0 && !r.output.contains("Error") && !r.output.contains("Exception")) {
                            ok = true
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Opening $what...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Couldn't open $what through Wireless debugging, trying directly.",
                        Toast.LENGTH_LONG
                    ).show()
                    tryOpen(what, *direct.toTypedArray())
                }
            }
        }
    }

    private fun setAutostart(on: Boolean) {
        prefs().edit().putBoolean("autostart", on).apply()
        if (on) {
            if (connectPort != null) {
                runShell(
                    "appops set $packageName SYSTEM_ALERT_WINDOW allow",
                    "Start at boot is on and allowed"
                )
            } else if (!Settings.canDrawOverlays(this)) {
                tryOpen(
                    "the permission screen",
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                Toast.makeText(this, "Start at boot is on", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Start at boot is off", Toast.LENGTH_SHORT).show()
        }
        showTab(3)
    }

    private fun toolsPage(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        col.addView(heading("Wireless debugging"))
        val statusText = TextView(this).apply {
            text = status
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        }
        statusView = statusText
        col.addView(statusText)

        val connRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connRow.addView(bigButton("Connect", accent) { connect() },
            LinearLayout.LayoutParams(dp(160), dp(56)).apply { setMargins(0, 0, dp(12), 0) })
        connRow.addView(bigButton("Pair again", panel) { showPairing() },
            LinearLayout.LayoutParams(dp(160), dp(56)))
        col.addView(connRow)

        col.addView(note("If it can't find the port by itself, type the port from the Wireless debugging screen:"))
        val portField = EditText(this).apply {
            hint = "Connection port"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            background = round(panel, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(portField, LinearLayout.LayoutParams(dp(240), dp(56)))
        col.addView(bigButton("Use this port", panel) {
            val p = portField.text.toString().trim().toIntOrNull()
            if (p == null) {
                setStatus("Type a port number first")
            } else {
                setStatus("Trying port $p...")
                thread {
                    val ok = testConnection(p)
                    runOnUiThread {
                        if (ok) { connectPort = p; setStatus("Connected (port $p)") }
                        else setStatus("Couldn't connect on port $p")
                    }
                }
            }
        }, LinearLayout.LayoutParams(dp(200), dp(56)).apply { topMargin = dp(8) })

        col.addView(heading("Android settings"))
        col.addView(note("Opens the headset's real Android settings, not Meta Horizon settings. Works best after the status above says Connected, because it opens them through Wireless debugging."))
        val setRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setRow1.addView(bigButton("Android settings", panel) {
            openSettings(
                "Android settings", cmdHome,
                listOf(
                    comp("com.android.settings.homepage.SettingsHomepageActivity"),
                    comp("com.android.settings.Settings")
                )
            )
        }, LinearLayout.LayoutParams(dp(240), dp(56)).apply { setMargins(0, 0, dp(12), 0) })
        setRow1.addView(bigButton("Apps list", panel) {
            openSettings(
                "the apps list", cmdApps,
                listOf(
                    comp("com.android.settings.UsageStatsActivity"),
                    Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
                    comp("com.android.settings.Settings\$ManageApplicationsActivity")
                )
            )
        }, LinearLayout.LayoutParams(dp(200), dp(56)))
        col.addView(setRow1)
        val setRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setRow2.addView(bigButton("Developer options", panel) {
            openSettings(
                "developer options", cmdDev,
                listOf(
                    comp("com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                )
            )
        }, LinearLayout.LayoutParams(dp(240), dp(56)).apply { setMargins(0, dp(10), dp(12), 0) })
        setRow2.addView(bigButton("Wireless debugging", panel) {
            openSettings(
                "wireless debugging", cmdWireless,
                listOf(
                    comp("com.android.settings.Settings\$WirelessDebuggingActivity"),
                    comp("com.android.settings.SubSettings")
                        .putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
                )
            )
        }, LinearLayout.LayoutParams(dp(250), dp(56)).apply { topMargin = dp(10) })
        col.addView(setRow2)

        col.addView(heading("Start when the headset turns on"))
        col.addView(note("Opens Digital Library a few seconds after the headset powers on. Meta doesn't officially support this, so it may not work on every headset."))
        val on = prefs().getBoolean("autostart", false)
        col.addView(
            bigButton(if (on) "Start at boot: ON" else "Start at boot: OFF", if (on) accent else panel) {
                setAutostart(!on)
            },
            LinearLayout.LayoutParams(dp(260), dp(56))
        )

        col.addView(heading("Refresh rate and FPS (Hz)"))
        col.addView(note("Quest ties FPS to the refresh rate. Meta lists 72, 80, 90 and 120 for Quest 3. Restart a running game after changing it."))
        col.addView(buttonRow(listOf(72, 75, 80, 90, 120, 200)) { hz ->
            runShell("setprop debug.oculus.refreshRate $hz", "Refresh rate and FPS set to $hz")
        })

        col.addView(heading("Tile style"))
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        styleRow.addView(bigButton("Icons", if (style == Kind.ICON) accent else panel) { setStyle(Kind.ICON) },
            LinearLayout.LayoutParams(dp(160), dp(56)).apply { setMargins(0, 0, dp(12), 0) })
        styleRow.addView(bigButton("Banners", if (style == Kind.BANNER) accent else panel) { setStyle(Kind.BANNER) },
            LinearLayout.LayoutParams(dp(160), dp(56)))
        col.addView(styleRow)

        col.addView(heading("Store pictures"))
        val artText = TextView(this).apply {
            text = art.stats()
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        }
        artStatsView = artText
        col.addView(artText)
        col.addView(bigButton("Refresh pictures", panel) {
            art.refresh(allApps.map { it.pkg })
            artText.text = art.stats()
        }, LinearLayout.LayoutParams(dp(220), dp(56)))
        col.addView(note("Pictures and names come from the MetaMetadata project, the same source Lightning Launcher uses. Needs internet. Long-press any tile to move it between Apps and Games, or to open its app info."))
        return scroll
    }

    private fun buttonRow(values: List<Int>, onClick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { v ->
            val b = Button(this).apply {
                text = v.toString()
                textSize = 20f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = round(panel, 14)
                setOnClickListener { onClick(v) }
            }
            row.addView(b, LinearLayout.LayoutParams(dp(110), dp(60)).apply { setMargins(0, 0, dp(10), 0) })
        }
        return row
    }

    // ---------- Wireless debugging ----------

    private fun setStatus(text: String) {
        runOnUiThread {
            status = text
            statusView?.text = text
        }
    }

    private fun testConnection(port: Int): Boolean {
        return try {
            Kadb.create("127.0.0.1", port).use { it.shell("echo ok").exitCode == 0 }
        } catch (e: Throwable) {
            false
        }
    }

    /** Looks for Wireless debugging and connects. Retries a few times, useful right after boot. */
    private fun connect(retries: Int = 0) {
        setStatus("Looking for Wireless debugging...")
        findConnectPort { port ->
            if (port == null) {
                if (retries > 0) {
                    setStatus("Waiting for Wireless debugging...")
                    handler.postDelayed({ connect(retries - 1) }, 10000)
                } else {
                    setStatus("Wireless debugging not found. Turn it on, then tap Connect in Tools.")
                }
                return@findConnectPort
            }
            thread {
                val ok = testConnection(port)
                runOnUiThread {
                    if (ok) {
                        connectPort = port
                        setStatus("Connected (port $port)")
                    } else if (retries > 0) {
                        setStatus("Couldn't connect yet, retrying...")
                        handler.postDelayed({ connect(retries - 1) }, 10000)
                    } else {
                        setStatus("Found port $port but couldn't connect. Try Pair again in Tools.")
                    }
                }
            }
        }
    }

    private fun findConnectPort(onResult: (Int?) -> Unit) {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        var done = false
        lateinit var listener: NsdManager.DiscoveryListener
        val finish = { port: Int? ->
            if (!done) {
                done = true
                try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
                onResult(port)
            }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        finish(info.port)
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { finish(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            finish(null)
            return
        }
        handler.postDelayed({ finish(null) }, 8000)
    }

    private fun runShell(cmd: String, okMsg: String) {
        val port = connectPort
        if (port == null) {
            Toast.makeText(this, "Not connected yet. Open Tools and tap Connect.", Toast.LENGTH_LONG).show()
            return
        }
        thread {
            val msg = try {
                val r = Kadb.create("127.0.0.1", port).use { it.shell(cmd) }
                if (r.exitCode == 0) okMsg else "Failed: ${r.output}"
            } catch (e: Throwable) {
                "Couldn't connect. Open Tools and tap Connect."
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------- App loading ----------

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        val vrPkgs = HashSet<String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val vr = Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            found.putIfAbsent(ri.activityInfo.packageName, ri)
        }
        for (ri in pm.queryIntentActivities(vr, 0)) {
            vrPkgs.add(ri.activityInfo.packageName)
            found.putIfAbsent(ri.activityInfo.packageName, ri)
        }
        return found.values
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                @Suppress("DEPRECATION")
                val game = ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
                val pkg = ai.packageName
                AppEntry(
                    pm.getApplicationLabel(ai).toString(),
                    pkg,
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    vrPkgs.contains(pkg) && !pkg.startsWith("com.oculus.os."),
                    game
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    // ---------- helpers ----------

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        background = round(color, 14)
        setOnClickListener { onClick() }
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 22f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.LTGRAY)
        textSize = 14f
        setPadding(0, 0, 0, dp(8))
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
