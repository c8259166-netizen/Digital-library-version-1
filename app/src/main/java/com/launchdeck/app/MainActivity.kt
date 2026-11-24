package com.launchdeck.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import dadb.Dadb
import kotlin.concurrent.thread

data class AppEntry(val label: String, val pkg: String, val isGame: Boolean)

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
    private lateinit var content: FrameLayout
    private val tabButtons = mutableListOf<Button>()
    private var adbPort = 5555

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allApps = loadApps()

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

        val frame = FrameLayout(this).apply { setBackgroundColor(bg) }
        frame.addView(ShapesView(this), FrameLayout.LayoutParams(-1, -1))
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        showTab(0)
    }

    private fun showTab(index: Int) {
        tabButtons.forEachIndexed { i, b -> b.background = round(if (i == index) accent else panel, 14) }
        content.removeAllViews()
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
                val icon = ImageView(this@MainActivity)
                try { icon.setImageDrawable(packageManager.getApplicationIcon(e.pkg)) } catch (_: Exception) {}
                tile.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)))
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
        grid.setOnItemClickListener { _, _, pos, _ ->
            packageManager.getLaunchIntentForPackage(shown[pos].pkg)?.let { startActivity(it) }
        }
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

    // ---------- Tools page ----------

    private fun toolsPage(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        col.addView(heading("Wireless debugging port"))
        col.addView(note("Turn on Wireless debugging in Quest developer settings, pair once, then enter the port shown there."))
        val portField = EditText(this).apply {
            setText(adbPort.toString())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            background = round(panel, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(portField, LinearLayout.LayoutParams(dp(200), dp(56)))

        col.addView(heading("Refresh rate (Hz)"))
        col.addView(buttonRow(listOf(75, 90, 120, 200)) { hz ->
            adbPort = portField.text.toString().toIntOrNull() ?: adbPort
            runShell("setprop debug.oculus.refreshRate $hz", "Refresh rate $hz Hz")
        })

        col.addView(heading("FPS"))
        col.addView(buttonRow(listOf(75, 90, 120, 200)) { fps ->
            adbPort = portField.text.toString().toIntOrNull() ?: adbPort
            runShell("setprop debug.oculus.fps $fps", "FPS $fps")
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

    private fun runShell(cmd: String, okMsg: String) {
        thread {
            val msg = try {
                Dadb.create("localhost", adbPort).use { it.shell(cmd) }
                okMsg
            } catch (e: Exception) {
                "Couldn't connect: check Wireless debugging and the port"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------- helpers ----------

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map {
                @Suppress("DEPRECATION")
                val game = it.category == ApplicationInfo.CATEGORY_GAME ||
                    (it.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                AppEntry(pm.getApplicationLabel(it).toString(), it.packageName, game)
            }
            .sortedBy { it.label.lowercase() }
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
