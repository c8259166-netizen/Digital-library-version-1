package com.launchdeck.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import kotlin.concurrent.thread

data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val isGame: Boolean
)

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

    // Wireless debugging state
    private var connectPort: Int? = null
    private var status = "Not connected"
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allApps = loadApps()

        val frame = FrameLayout(this).apply { setBackgroundColor(bg) }
        frame.addView(ShapesView(this), FrameLayout.LayoutParams(-1, -1))
        screen = FrameLayout(this)
        frame.addView(screen, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)

        showPairing()
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
        col.addView(heading("Connect Wireless debugging"))
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
                            setStatus("Paired. Looking for the connection...")
                            connect()
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
        when (index) {
            0 -> content.addView(appGrid(allApps))
            1 -> content.addView(appGrid(allApps.filter { !it.isGame }))
            2 -> content.addView(appGrid(allApps.filter { it.isGame }))
            3 -> content.addView(toolsPage())
        }
    }

    // ---------- App list with search bar ----------

    private fun appGrid(source: List<AppEntry>): View {
        var shown = source
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

        val grid = GridView(this).apply {
            numColumns = 6
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
                val tile = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = round(panel, 18)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                }
                val icon = iconFor(e)
                if (icon != null) {
                    val img = ImageView(this@MainActivity)
                    img.setImageDrawable(icon)
                    tile.addView(img, LinearLayout.LayoutParams(dp(72), dp(72)))
                } else {
                    tile.addView(letterTile(e), LinearLayout.LayoutParams(dp(72), dp(72)))
                }
                tile.addView(TextView(this@MainActivity).apply {
                    text = e.label
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 2
                })
                return tile
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                shown = if (q.isEmpty()) source else source.filter { it.label.lowercase().contains(q) }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        return col
    }

    private fun launch(e: AppEntry) {
        val i = packageManager.getLaunchIntentForPackage(e.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(e.component)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (ex: Exception) {
            Toast.makeText(this, "Couldn't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Icons ----------

    private fun iconFor(e: AppEntry): Drawable? {
        if (iconCache.containsKey(e.pkg)) return iconCache[e.pkg]
        val pm = packageManager
        val defaultState = try { pm.defaultActivityIcon.constantState } catch (_: Exception) { null }
        var result: Drawable? = null
        val tries = listOf<() -> Drawable?>(
            { pm.getActivityIcon(e.component) },
            { pm.getApplicationIcon(e.pkg) }
        )
        for (t in tries) {
            val d = try { t() } catch (_: Exception) { null }
            if (d != null) {
                val state = d.constantState
                val isDefault = state != null && defaultState != null && state == defaultState
                if (!isDefault) { result = d; break }
            }
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
                text = e.label.firstOrNull()?.uppercase() ?: "?"
                setTextColor(Color.WHITE)
                textSize = 30f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    // ---------- Tools page ----------

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

        col.addView(heading("Refresh rate and FPS (Hz)"))
        col.addView(note("Quest ties FPS to the refresh rate. Meta lists 72, 80, 90 and 120 for Quest 3. Restart a running game after changing it."))
        col.addView(buttonRow(listOf(72, 75, 80, 90, 120, 200)) { hz ->
            runShell("setprop debug.oculus.refreshRate $hz", "Refresh rate and FPS set to $hz")
        })
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

    private fun connect() {
        setStatus("Looking for Wireless debugging...")
        findConnectPort { port ->
            if (port == null) {
                setStatus("Wireless debugging not found. Turn it on, then tap Connect in Tools.")
                return@findConnectPort
            }
            thread {
                val ok = testConnection(port)
                runOnUiThread {
                    if (ok) {
                        connectPort = port
                        setStatus("Connected (port $port)")
                    } else {
                        setStatus("Found port $port but couldn't connect. Try Pair again.")
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
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val vr = Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")
        for (i in listOf(launcher, vr)) {
            for (ri in pm.queryIntentActivities(i, 0)) {
                found.putIfAbsent(ri.activityInfo.packageName, ri)
            }
        }
        return found.values
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                @Suppress("DEPRECATION")
                val game = ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
                AppEntry(
                    pm.getApplicationLabel(ai).toString(),
                    ai.packageName,
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
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
