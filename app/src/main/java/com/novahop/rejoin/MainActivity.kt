package com.novahop.rejoin

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var accountsBox: LinearLayout
    private lateinit var logView: TextView
    private val rows = HashMap<String, Button>()
    private var rowsKey = ""
    @Volatile private var setupInfo = "Tap Setup once, then Start."

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapping.init(this)
        Settings.init(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad / 2, 0, pad / 2)
        }
        buttons.addView(Button(this).apply {
            text = "Setup"
            setOnClickListener { setup() }
        })
        startBtn = Button(this).apply {
            text = "Start"
            setOnClickListener { toggle() }
        }
        buttons.addView(startBtn)
        root.addView(buttons)
        val buttons2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, pad / 2)
        }
        buttons2.addView(Button(this).apply {
            text = "Game / private server"
            setOnClickListener { gameDialog() }
        })
        buttons2.addView(Button(this).apply {
            text = "Reset matches"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Forget which app every account is in?")
                    .setPositiveButton("Reset") { _, _ -> Mapping.clear(); rowsKey = "" }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        root.addView(buttons2)

        root.addView(TextView(this).apply {
            text = "Accounts (tap one to change its app)"
            setTypeface(null, Typeface.BOLD)
        })
        accountsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(accountsBox)

        root.addView(TextView(this).apply {
            text = "Log"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad / 2, 0, 0)
        })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
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
                sb.append("Heartbeat added to:\n")
                done.forEach { sb.append("  $it\n") }
                sb.append("Now join the game once on every Roblox app.\n")
            }
            val n = Mapping.importTermux()
            if (n > 0) sb.append("Imported $n account matches from Termux.\n")
            val apps = WatcherService.findApps()
            WatcherState.apps = apps
            sb.append("Roblox apps: ${apps.joinToString(", ")}")
            setupInfo = sb.toString()
            rowsKey = ""
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
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else if (Build.VERSION.SDK_INT < 30) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun describe(a: Account): String {
        val st = when {
            a.pkg == null -> "NOT MATCHED, tap to pick its app"
            a.age >= WatcherService.STALE -> "no heartbeat for ${a.age}s"
            a.state == "error" -> "error: ${a.msg}"
            else -> "OK"
        }
        return "${a.name}\n${a.pkg ?: "?"}\n$st"
    }

    private fun refresh() {
        val on = WatcherState.running
        startBtn.text = if (on) "Stop" else "Start"
        status.text = (if (on) "Watcher: RUNNING" else "Watcher: stopped") +
            "\nGame: place ${Settings.place}, private server: ${if (Settings.ps.isEmpty()) "no" else "yes"}" +
            "\n" + setupInfo

        val accounts = WatcherState.accounts
        val key = accounts.joinToString(",") { it.uid }
        if (key != rowsKey) {
            rowsKey = key
            rows.clear()
            accountsBox.removeAllViews()
            if (accounts.isEmpty()) {
                accountsBox.addView(TextView(this).apply {
                    text = "No accounts seen yet. Start the watcher and join the game."
                })
            }
            for (a in accounts) {
                val b = Button(this).apply {
                    setAllCaps(false)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { pick(a.uid, a.name) }
                }
                rows[a.uid] = b
                accountsBox.addView(b)
            }
        }
        for (a in accounts) rows[a.uid]?.text = describe(a)

        logView.text = WatcherState.logText()
    }

    private fun gameDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
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
            setPadding(pad, pad / 2, pad, 0)
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
