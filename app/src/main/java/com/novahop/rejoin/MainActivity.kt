package com.novahop.rejoin

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0F0F14")
    private val card = Color.parseColor("#1B1B25")
    private val card2 = Color.parseColor("#2A2A38")
    private val accent = Color.parseColor("#A855F7")
    private val textMain = Color.parseColor("#F1F1F5")
    private val textMuted = Color.parseColor("#9A9AAD")
    private val green = Color.parseColor("#34D399")
    private val yellow = Color.parseColor("#FBBF24")
    private val red = Color.parseColor("#EF4444")

    private class Row(val sub: TextView, val status: TextView)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var dot: GradientDrawable
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var startBtn: TextView
    private lateinit var setupText: TextView
    private lateinit var accountsBox: LinearLayout
    private lateinit var logView: TextView
    private val rows = HashMap<String, Row>()
    private var rowsKey = "-"
    @Volatile private var setupInfo = "First time here? Tap Setup, then Start."

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }

    private fun pressable(color: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color), null)

    private fun block(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun button(s: String, color: Int, height: Int, size: Float, onClick: () -> Unit): TextView =
        label(s, size, textMain, true).apply {
            gravity = Gravity.CENTER
            background = pressable(color)
            minHeight = dp(height)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapping.init(this)
        Settings.init(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }

        root.addView(label("NovaHop Rejoin", 26f, textMain, true))

        dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(textMuted)
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(View(this).apply { background = dot }, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = label("Stopped", 15f, textMain, true).apply { setPadding(dp(8), 0, 0, 0) }
        statusRow.addView(statusText)
        root.addView(statusRow, block(6))

        infoText = label("", 13f, textMuted)
        root.addView(infoText, block(4))

        startBtn = button("START", accent, 64, 18f) { toggle() }
        root.addView(startBtn, block(18))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("Setup", "Game / server", "Reset matches")
        val clicks = listOf<() -> Unit>({ setup() }, { gameDialog() }, { resetDialog() })
        for (i in names.indices) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(10)
            actions.addView(button(names[i], card2, 52, 14f, clicks[i]), lp)
        }
        root.addView(actions, block(10))

        setupText = label("", 13f, textMuted).apply {
            background = rounded(card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(setupText, block(14))

        root.addView(label("ACCOUNTS", 12f, textMuted, true).apply { letterSpacing = 0.12f }, block(22))
        accountsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(accountsBox)

        root.addView(label("LOG", 12f, textMuted, true).apply { letterSpacing = 0.12f }, block(22))
        logView = label("", 11f, textMuted).apply {
            typeface = Typeface.MONOSPACE
            background = rounded(card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(logView, block(8))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun toggle() {
        if (WatcherState.running) {
            WatcherService.stop(this)
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            WatcherService.start(this)
        }
        ui.postDelayed({ refresh() }, 500)
    }

    private fun resetDialog() {
        AlertDialog.Builder(this)
            .setMessage("Forget which app every account is in?")
            .setPositiveButton("Reset") { _, _ ->
                Mapping.clear()
                rowsKey = "-"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // One tap: root check, permissions, heartbeat into Delta's autoexec, old Termux matches.
    private fun setup() {
        setupInfo = "Setting up..."
        refresh()
        Thread {
            val sb = StringBuilder()
            if (Shell.checkRoot()) {
                sb.append("Root: yes\n")
                Shell.run("pm grant $packageName android.permission.POST_NOTIFICATIONS")
                Shell.run("appops set $packageName SYSTEM_ALERT_WINDOW allow")
                Shell.run("dumpsys deviceidle whitelist +$packageName")
            } else {
                sb.append("Root: NO. Turn on root in the emulator/phone settings, then tap Setup again and tap Allow.\n")
                ui.post { askPermissions() }
            }
            val done = WatcherService.installHeartbeat()
            if (done.isEmpty()) {
                sb.append("Couldn't find Delta's autoexec folder. Open Delta once, then tap Setup again.\n")
            } else {
                sb.append("Heartbeat is in:\n")
                done.forEach { sb.append("  $it\n") }
                sb.append("Now join the game once on every Roblox app.\n")
            }
            val n = Mapping.importTermux()
            if (n > 0) sb.append("Imported $n account matches from Termux.\n")
            val apps = WatcherService.findApps()
            WatcherState.apps = apps
            sb.append("Roblox apps: ${apps.joinToString(", ")}")
            setupInfo = sb.toString()
            rowsKey = "-"
            ui.post { refresh() }
        }.start()
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        try {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else if (Build.VERSION.SDK_INT < 30) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun statusColor(s: String): Int = when {
        s == "OK" -> green
        s.startsWith("loading") || s == "restarting" || s.startsWith("not matched") -> yellow
        else -> red
    }

    private fun refresh() {
        val on = WatcherState.running
        dot.setColor(if (on) green else textMuted)
        statusText.text = if (on) "Running" else "Stopped"
        startBtn.text = if (on) "STOP" else "START"
        startBtn.background = pressable(if (on) red else accent)
        val game = if (Settings.place == Settings.BEE_SWARM) "Bee Swarm Simulator" else "Place ${Settings.place}"
        infoText.text = game + "  ·  " + (if (Settings.ps.isEmpty()) "public servers" else "private server")
        setupText.text = setupInfo

        val accounts = WatcherState.accounts
        val key = accounts.joinToString(",") { it.uid }
        if (key != rowsKey) {
            rowsKey = key
            rows.clear()
            accountsBox.removeAllViews()
            if (accounts.isEmpty()) {
                accountsBox.addView(
                    label("No accounts yet. Tap Start and join the game.", 13f, textMuted).apply {
                        background = rounded(card)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    },
                    block(8)
                )
            }
            for (a in accounts) {
                val sub = label("", 12f, textMuted)
                val st = label("", 14f, textMain, true)
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pressable(card)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isClickable = true
                    setOnClickListener { pick(a.uid, a.name) }
                    addView(label(a.name, 16f, textMain, true))
                    addView(sub)
                    addView(st)
                }
                rows[a.uid] = Row(sub, st)
                accountsBox.addView(box, block(8))
            }
        }
        for (a in accounts) {
            val r = rows[a.uid] ?: continue
            r.sub.text = (a.pkg ?: "tap to pick its app") + "  ·  restarts: " + a.restarts
            r.status.text = a.status
            r.status.setTextColor(statusColor(a.status))
        }

        logView.text = WatcherState.logText().ifEmpty { "Nothing yet." }
    }

    private fun gameDialog() {
        val game = EditText(this).apply {
            hint = "Game link or place ID"
            setSingleLine()
            setText(Settings.place)
        }
        val ps = EditText(this).apply {
            hint = "Private server link"
            setSingleLine()
            setText(Settings.psRaw)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(TextView(this@MainActivity).apply { text = "Game (empty = Bee Swarm Simulator)" })
            addView(game)
            addView(TextView(this@MainActivity).apply { text = "Private server link (empty = public servers)" })
            addView(ps)
        }
        AlertDialog.Builder(this)
            .setTitle("Game and private server")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val p = Settings.parseGame(game.text.toString())
                val s = Settings.parsePs(ps.text.toString())
                if (p == null || s == null) {
                    Toast.makeText(this, "Didn't understand that link, nothing changed", Toast.LENGTH_LONG).show()
                } else {
                    Settings.save(p, s, ps.text.toString().trim())
                    WatcherState.log("Saved: place $p, private server: ${if (s.isEmpty()) "no" else "yes"}")
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pick(uid: String, name: String) {
        val apps = WatcherState.apps
        if (apps.isEmpty()) return
        val current = Mapping.get(uid)
        val all = Mapping.all()
        val labels = apps.map { p ->
            val owner = all.entries.firstOrNull { it.value == p && it.key != uid }?.key
            if (owner != null) "$p  (used by ${WatcherState.accounts.firstOrNull { it.uid == owner }?.name ?: owner})" else p
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which Roblox app is $name?")
            .setSingleChoiceItems(labels, apps.indexOf(current)) { d, i ->
                Mapping.set(uid, apps[i])
                WatcherState.log("Saved: $name is ${apps[i]}")
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
