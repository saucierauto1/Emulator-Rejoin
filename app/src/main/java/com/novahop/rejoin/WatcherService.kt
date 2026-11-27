package com.novahop.rejoin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

// Same rules as novahop_rejoin.sh, running as a background service instead of in Termux.
class WatcherService : Service() {

    companion object {
        const val PLACE = "1537690962"   // Bee Swarm Simulator
        const val ERROR_WAIT = 20        // error screen up this many seconds -> restart
        val HOPPING = Regex(
            "teleport|experience is full|server is full|game is full|full server|unable to join|requested experience|joining|restricted|no longer available|\\b(769|770|771|772|773)\\b",
            RegexOption.IGNORE_CASE
        )
        const val STALE = 120            // no heartbeat for this many seconds -> restart
        const val BOOT_WAIT = 150        // after a restart, give the app this long to load
        const val GIVEUP = 3600          // unmatched account's heartbeat older than this = not in use
        const val CHECK = 5
        const val FRESH = 20             // heartbeat newer than this = the account is in the game right now
        const val RETRY = 900            // after 3 restarts that didn't bring it back, only try again this often

        val HEARTBEAT_LINE =
            "loadstring(game:HttpGet(\"https://gist.githubusercontent.com/saucierauto1/4e760d58e39c190bb537f599d9d788a8/raw/NovaHop_Heartbeat.lua\"))()"

        private val AD = Regex(
            "AdActivity|ads\\.|\\.ads|applovin|unity3d|ironsource|vungle|mbridge|pangle|bytedance|chartboost|inmobi|interstitial|adcolony|startapp|fyber|admob",
            RegexOption.IGNORE_CASE
        )
        private val ADPKGS = Regex(
            "chrome|browser|vending|webview|opera|firefox|brave|duckduckgo|ucmobile|\\.edge|mibrowser|sbrowser|yandex|kiwi|ads|adservices",
            RegexOption.IGNORE_CASE
        )
        private val LEAVE_ALONE = Regex(
            "novahop|termux|launcher|systemui|settings|permission|packageinstaller",
            RegexOption.IGNORE_CASE
        )

        fun start(ctx: Context) {
            val i = Intent(ctx, WatcherService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatcherService::class.java))
        }

        fun findApps(): List<String> {
            val l = Shell.run("pm list packages").lines()
                .map { it.trim().removePrefix("package:") }
                .filter { it.contains("roblox", true) }
                .sorted()
            return if (l.isEmpty()) listOf("com.roblox.client") else l
        }

        private fun autoexecDirs(): List<String> = Shell.run(
            "find /storage/emulated/0 -maxdepth 5 -type d -iname 'autoexec*'"
        ).lines().map { it.trim() }.filter { it.isNotEmpty() }

        // FPS cap through the executor: novahop_fps.lua in every autoexec folder (removed when the cap is 0)
        fun applyFps(): Int {
            val cap = Settings.fpsCap
            var n = 0
            for (d in autoexecDirs()) {
                if (cap > 0) {
                    Shell.run("echo 'if setfpscap then setfpscap($cap) end' > '$d/novahop_fps.lua'")
                    if (Shell.run("cat '$d/novahop_fps.lua'").contains("setfpscap($cap)")) n++
                } else {
                    Shell.run("rm -f '$d/novahop_fps.lua'")
                }
            }
            return n
        }

        // Puts heartbeat.lua into every Delta autoexec folder it can find. Returns the folders.
        fun installHeartbeat(): List<String> {
            val dirs = autoexecDirs()
            val done = ArrayList<String>()
            for (d in dirs) {
                if (Shell.run("grep -ls NovaHop_Heartbeat '$d'/*").trim().isNotEmpty()) { done.add(d); continue }
                Shell.run("echo '$HEARTBEAT_LINE' > '$d/heartbeat.lua'")
                if (Shell.run("cat '$d/heartbeat.lua'").contains("NovaHop_Heartbeat")) done.add(d)
            }
            return done
        }
    }

    @Volatile private var alive = false
    private var main: Thread? = null
    private var guard: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    private var apps: List<String> = emptyList()
    private var hbDirs: List<String> = emptyList()
    private val comp = HashMap<String, String>()
    private val last = HashMap<String, Long>()
    private val errSince = HashMap<String, Long>()
    private val names = HashMap<String, String>()
    private val warned = HashSet<String>()
    private val restarts = HashMap<String, Int>()
    private val fails = HashMap<String, Int>()
    private val probed = HashMap<String, Long>()
    private val beats = HashMap<String, String>()     // last heartbeat line that could be read, per account
    private val errMsg = HashMap<String, String>()    // the error text the timer started with
    private val errFirst = HashMap<String, Long>()    // when this error spell began (hop errors can't push it back)
    private val reason = HashMap<String, String>()    // why each account was last restarted
    private val ramHits = HashMap<String, Int>()      // RAM-limit restarts in a row
    private val queue = ArrayList<Pair<String, String>>()   // (uid, why) waiting for their launch slot
    private var nextLaunch = 0L
    private var reporter: Thread? = null
    private var lastHookError = ""
    @Volatile private var front: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (alive) return START_STICKY
        alive = true
        Mapping.init(this)
        Settings.init(this)
        foreground()
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novahop:watcher").also { it.acquire() }
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("on", true).apply()
        WatcherState.running = true
        main = Thread { try { loop() } catch (e: InterruptedException) { } }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        main?.interrupt()
        guard?.interrupt()
        reporter?.interrupt()
        try { wake?.release() } catch (e: Exception) { }
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("on", false).apply()
        WatcherState.running = false
        WatcherState.log("Watcher stopped")
        super.onDestroy()
    }

    private fun foreground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("watcher", "Watcher", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "watcher")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("NovaHop Rejoin is running")
            .setContentText("Tap to open")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun say(s: String) = WatcherState.log(s)
    private fun now() = System.currentTimeMillis() / 1000

    // ------------------------------------------------------------ heartbeat folders
    private fun findHb(): Boolean {
        val cmd = StringBuilder(
            "for d in /storage/emulated/0/Delta/Workspace /storage/emulated/0/Delta/workspace /storage/emulated/0/Delta /sdcard/Delta/Workspace; do " +
                "[ -d \"\$d/NovaHop/hb\" ] && echo \"\$d/NovaHop/hb\"; done; " +
                "find /storage/emulated/0 -maxdepth 7 -type d -path '*NovaHop/hb'"
        )
        if (Shell.root) cmd.append("; find /data/media/0 /data/data /data/user/0 -maxdepth 8 -type d -path '*NovaHop/hb'")
        hbDirs = Shell.run(cmd.toString()).lines().map { it.trim() }.filter { it.startsWith("/") }
            .distinctBy { it.replace("/sdcard/", "/storage/emulated/0/").replace("/data/media/0/", "/storage/emulated/0/").lowercase() }
        return hbDirs.isNotEmpty()
    }

    // newest heartbeat line per account, across all folders
    private fun collect(): Map<String, String> {
        val best = HashMap<String, String>()
        for (d in hbDirs) {
            val out = Shell.run("for f in '$d'/*.txt; do echo \"\$f=\$(cat \"\$f\")\"; done")
            for (l in out.lines()) {
                val i = l.indexOf(".txt=")
                if (i < 0) continue
                val uid = l.substring(0, i).substringAfterLast('/')
                if (uid.isEmpty() || !uid.all { it.isDigit() }) continue
                val line = l.substring(i + 5)
                val ts = line.substringBefore('|').toLongOrNull() ?: continue
                val old = best[uid]?.substringBefore('|')?.toLongOrNull()
                if (old == null || ts > old) best[uid] = line
            }
        }
        // a file caught half written keeps its last good line, so the account doesn't vanish for a moment
        for ((u, l) in best) {
            val old = beats[u]?.substringBefore('|')?.toLongOrNull()
            if (old == null || (l.substringBefore('|').toLongOrNull() ?: 0L) >= old) beats[u] = l
        }
        return HashMap(beats)
    }

    // ------------------------------------------------------------ which app is which account
    private fun running(p: String) = Shell.run("pidof $p").trim().isNotEmpty()

    // START: open every clone that isn't open yet, Settings.launchGap seconds apart
    private fun openAll() {
        val map = Mapping.all()                       // uid -> app
        val targets = if (map.isNotEmpty()) map.values.distinct().filter { apps.contains(it) } else apps
        val toOpen = targets.filter { !running(it) }
        if (toOpen.isEmpty()) return
        say("Opening ${toOpen.size} Roblox app(s), ${Settings.launchGap}s apart")
        for ((i, p) in toOpen.withIndex()) {
            if (!alive) return
            val owner = map.entries.firstOrNull { it.value == p }?.key
            openLink(p, owner)
            Thread { try { clearAds(p, owner) } catch (e: InterruptedException) { } }.start()
            for ((u, pk) in map) if (pk == p) last[u] = now()
            if (i < toOpen.size - 1) Thread.sleep(Settings.launchGap * 1000L)
        }
    }

    // free space: Roblox's cache folders in every clone (the game keeps running)
    private fun clearCache() {
        if (!Shell.root) { say("Clearing the cache needs root"); return }
        var freed = 0L
        for (p in apps) {
            val before = Shell.run("du -sk /data/data/$p/cache /data/data/$p/code_cache").lines()
                .sumOf { it.trim().substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L }
            Shell.run("rm -rf /data/data/$p/cache/* /data/data/$p/code_cache/*")
            freed += before
        }
        say("Cleared Roblox cache in ${apps.size} app(s), about ${freed / 1024} MB")
    }
    private fun freeRunning() = apps.filter { !Mapping.taken(it) && running(it) }

    // Where Roblox itself says this account is logged in: the one app whose own data mentions the UserId (needs root)
    private fun probe(uid: String): String? {
        if (!Shell.root || apps.size < 2) return null
        val cmd = apps.joinToString("; ") {
            "grep -rqw '$uid' /data/data/$it/shared_prefs /data/data/$it/files/appData/LocalStorage && echo $it"
        }
        val hits = Shell.run(cmd).lines().map { it.trim() }.filter { apps.contains(it) }
        return if (hits.size == 1) hits[0] else null
    }

    private fun match(uid: String, fresh: Boolean, freshUnmatched: Int): Boolean {
        if (apps.size == 1) {
            if (Mapping.taken(apps[0]) && !fresh) return false
            Mapping.set(uid, apps[0])
            say("${names[uid]} ($uid) is ${apps[0]} (only Roblox app)")
            return true
        }
        // only safe when exactly one account is writing heartbeats right now and exactly one unmatched app is open
        if (!fresh || freshUnmatched != 1) return false
        val fr = freeRunning()
        if (fr.size == 1) {
            Mapping.set(uid, fr[0])
            say("${names[uid]} ($uid) is ${fr[0]} (only unmatched app open)")
            return true
        }
        return false
    }

    // ------------------------------------------------------------ restart one app
    // the exact screen inside the app that opens roblox:// links (so Android never shows an "Open with" box)
    private fun comp(p: String): String {
        comp[p]?.let { return it }
        var c = ""
        var s = false
        var f = false
        val scheme = Regex("^ *[A-Za-z0-9.+-]+:$")
        for (l in Shell.run("dumpsys package $p").lines()) {
            if (l.contains("Schemes:")) s = true
            if (s && l.trim() == "roblox:") { f = true; continue }
            if (f && scheme.matches(l)) f = false
            if (f) {
                val m = Regex(Regex.escape(p) + "/[^ ]*").find(l)
                if (m != null) { c = m.value; break }
            }
        }
        if (c.isEmpty()) {
            c = Shell.run("cmd package resolve-activity --brief -a android.intent.action.VIEW -d 'roblox://placeId=$PLACE' -p $p")
                .lines().lastOrNull { it.contains("/") }?.replace("\r", "")?.replace(" ", "") ?: ""
            if (c.startsWith("android/") || c.contains("ResolverActivity") || c.contains("ChooserActivity")) c = ""
        }
        comp[p] = c
        return c
    }

    // uid: the account in this clone, so it joins its own private server if it has one
    private fun openLink(p: String, uid: String?) {
        val c = comp(p)
        val link = Settings.link(uid)
        val out = if (c.isNotEmpty()) {
            Shell.run("am start -n '$c' -a android.intent.action.VIEW -d '$link'")
        } else {
            say("Couldn't find the link screen in $p, sending the link with -p")
            Shell.run("am start -a android.intent.action.VIEW -d '$link' -p $p")
        }
        if (out.contains("Error")) say("Android couldn't open $p: ${out.lines().firstOrNull { it.contains("Error") }?.trim()}")
    }

    // the screen in front right now, e.g. com.roblox.client/com.google.android.gms.ads.AdActivity
    private fun topAct(): String {
        val l = Shell.run("dumpsys activity activities | grep -m 1 -E 'mResumedActivity|topResumedActivity|ResumedActivity'")
        return Regex("[A-Za-z0-9_.]*/[A-Za-z0-9_.$]*").find(l)?.value ?: ""
    }

    private fun isAd(a: String) = AD.containsMatchIn(a)

    // after reopening an app: if an ad covers it, press Back until it's gone, then send the link again
    private fun clearAds(p: String, uid: String?) {
        if (!Shell.root) return
        var t = 0
        var seen = false
        while (alive && t < 90) {
            Thread.sleep(3000); t += 3
            val a = topAct()
            if (isAd(a)) {
                if (!seen) say("Ad on screen in $p ($a), closing it")
                seen = true
                Shell.run("input keyevent KEYCODE_BACK")
            } else if (seen) {
                say("Ad gone, sending the game link to $p again")
                Thread.sleep(2000)
                openLink(p, uid)
                return
            }
        }
    }

    // restarts wait in line so clones are launched one at a time, Settings.launchGap seconds apart
    private fun restart(uid: String, why: String) {
        if (queue.none { it.first == uid }) queue.add(uid to why)
    }

    private fun doRestart(uid: String, why: String) {
        val p = Mapping.get(uid) ?: return
        say("Restarting $p (${names[uid]}): $why")
        event("\uD83D\uDD04 Restarting **${names[uid]}** (${gameName(Settings.placeFor(uid))}): $why")
        if (Shell.root) { Shell.run("am force-stop $p"); Thread.sleep(3000) }
        openLink(p, uid)
        Thread { try { clearAds(p, uid) } catch (e: InterruptedException) { } }.start()
        last[uid] = now()
        errSince[uid] = 0
        restarts[uid] = (restarts[uid] ?: 0) + 1
        reason[uid] = why
    }

    private fun gameName(place: String) = if (place == Settings.BEE_SWARM) "Bee Swarm Simulator" else "Place $place"

    // a clone running twice: keep the one that started first, close the others
    private fun killDuplicates() {
        if (!Shell.root) return
        for (p in apps) {
            val rows = Shell.run("for x in \$(pidof $p); do echo \"\$x \$(cut -d' ' -f22 /proc/\$x/stat)\"; done")
                .lines().mapNotNull { l ->
                    val f = l.trim().split(" ")
                    if (f.size == 2) f[0].toLongOrNull()?.let { pid -> f[1].toLongOrNull()?.let { pid to it } } else null
                }
            if (rows.size < 2) continue
            val keep = rows.minByOrNull { it.second }!!.first
            for ((pid, _) in rows) if (pid != keep) Shell.run("kill -9 $pid")
            say("$p was running ${rows.size} times, closed the extra one(s)")
        }
    }

    // ------------------------------------------------------------ ad guard
    // Every 2s: if an ad or a browser is in front, switch back to the Roblox app that was in front last.
    private fun guardLoop() {
        if (!Shell.root) return
        var seen = ""
        while (alive) {
            Thread.sleep(2000)
            val a = topAct()
            val f = a.substringBefore('/')
            if (f.isEmpty()) continue
            if (apps.contains(f)) { front = f; continue }
            if (isAd(a) || ADPKGS.containsMatchIn(f)) {
                val p = front ?: apps[0]
                say("Ad over Roblox ($a), switching back to $p")
                Shell.run("monkey -p $p -c android.intent.category.LAUNCHER 1")
                Thread.sleep(3000)
            } else if (f != seen && !LEAVE_ALONE.containsMatchIn(a)) {
                say("In front now: $a (left alone; if this is the ad, report this line)")
            }
            seen = f
        }
    }

    // ------------------------------------------------------------ main loop
    private fun loop() {
        Shell.checkRoot()
        say("NovaHop rejoin watcher started (root: ${if (Shell.root) "yes" else "no"})")
        apps = findApps()
        WatcherState.apps = apps
        say("Roblox apps: ${apps.joinToString(" ")}")
        for (p in apps) say("  $p opens links with: ${comp(p).ifEmpty { "NOT FOUND" }}")
        if (!Shell.root) say("No root: a frozen Roblox can't be force-closed, only sent the join link. Turn on root for best results.")
        reporter = Thread { try { reportLoop() } catch (e: InterruptedException) { } }.also { it.start() }
        if (Settings.fpsCap > 0) say("FPS cap ${Settings.fpsCap} put in ${applyFps()} executor autoexec folder(s)")
        if (Settings.openAll) openAll()
        while (alive && !findHb()) {
            say("Waiting for NovaHop/hb (tap Setup, then join the game on every Roblox app)...")
            Thread.sleep(15000)
        }
        for (d in hbDirs) say("Watching $d")
        var lastScan = now()
        var lastClean = now()
        guard = Thread { try { guardLoop() } catch (e: InterruptedException) { } }.also { it.start() }

        while (alive) {
            Thread.sleep(CHECK * 1000L)
            // one bad pass (odd shell output, a half-read file) must never stop the watcher for good
            try { tick(lastScan, lastClean).let { lastScan = it.first; lastClean = it.second } }
            catch (e: InterruptedException) { throw e }
            catch (e: Exception) { say("Problem in watcher loop: ${e.message ?: e}") }
        }
    }

    // one pass over every account -> (lastScan, lastClean)
    private fun tick(scanAt: Long, cleanAt: Long): Pair<Long, Long> {
        var lastScan = scanAt
        var lastClean = cleanAt
        run {
            val now = now()
            val hours = Settings.cacheHours
            if (hours > 0 && now - lastClean >= hours * 3600L) {
                lastClean = now
                clearCache()
            }
            if (now - lastScan >= 60) {   // a new clone started writing heartbeats? pick up its folder
                lastScan = now
                val n = hbDirs.size
                val old = hbDirs
                if (!findHb()) hbDirs = old
                if (hbDirs.size > n) say("Found new heartbeat folder(s), now watching ${hbDirs.size}")
                killDuplicates()
            }
            val lim = Settings.ramLimit
            val ram = if (lim > 0) ramByApp() else emptyMap<String, Long>()
            val best = collect()
            val list = ArrayList<Account>()
            val pending = HashMap<String, List<String>>()
            val freshUnmatched = best.count { (u, ln) ->
                Mapping.get(u) == null && now - (ln.substringBefore('|').toLongOrNull() ?: 0L) < FRESH
            }
            for ((uid, line) in best) {
                val parts = line.split("|")
                val ts = parts[0].toLongOrNull() ?: continue
                val state = parts.getOrElse(1) { "" }
                val msg = parts.getOrElse(2) { "" }
                names[uid] = parts.getOrElse(3) { "" }.trim().ifEmpty { uid }
                val age = now - ts
                if (Mapping.get(uid) == null && age >= GIVEUP) continue   // an old file from an account you don't use any more
                val lp = probed[uid]
                if (!Mapping.isManual(uid) && (lp == null || (Mapping.get(uid) == null && now - lp >= 60))) {
                    probed[uid] = now
                    val p = probe(uid)
                    val was = Mapping.get(uid)
                    if (p != null && p != was) {
                        Mapping.set(uid, p)
                        say(
                            if (was == null) "${names[uid]} ($uid) is $p (found in the app's data)"
                            else "${names[uid]}: match fixed, $was -> $p (found in the app's data)"
                        )
                    }
                }
                if (Mapping.get(uid) == null) {
                    if (!match(uid, age < FRESH, freshUnmatched)) {
                        pending[uid] = apps.filter { !Mapping.taken(it) }
                        if (warned.add(uid)) say("Don't know which app ${names[uid]} is yet, pick it in the app")
                        list.add(Account(uid, names[uid]!!, null, "not matched", 0))
                        continue
                    }
                }
                val qi = queue.indexOfFirst { it.first == uid }
                if (qi >= 0) {
                    list.add(Account(uid, names[uid]!!, Mapping.get(uid), "waiting to launch (#${qi + 1})", restarts[uid] ?: 0, reason[uid] ?: ""))
                    continue
                }
                val l = last[uid]
                val st = when {
                    l != null && now - l < BOOT_WAIT -> "loading (${now - l}s)"
                    state == "error" && age < STALE -> "error: $msg"
                    age >= STALE && (fails[uid] ?: 0) >= 3 -> "not coming back, check its app"
                    age >= STALE -> "restarting"
                    else -> "OK"
                }
                list.add(Account(uid, names[uid]!!, Mapping.get(uid), st, restarts[uid] ?: 0, reason[uid] ?: ""))
                if (l != null && now - l < BOOT_WAIT) continue
                if (state == "error" && age < STALE) {
                    // NovaHop moving on to the next hop error = it's still working: the timer starts again
                    if ((errSince[uid] ?: 0L) == 0L || (msg != errMsg[uid] && HOPPING.containsMatchIn(msg))) {
                        if ((errSince[uid] ?: 0L) == 0L) {
                            say("${names[uid]}: error on screen: $msg")
                            errFirst[uid] = now
                        }
                        errSince[uid] = now
                        errMsg[uid] = msg
                    }
                    // a teleport / full-server error is NovaHop mid-hop and it retries by itself: give it longer,
                    // but never more than 5x that in one go (two hop errors taking turns forever = stuck)
                    val wait = if (HOPPING.containsMatchIn(msg)) Settings.hopWait else Settings.errorWait
                    if (now - errSince[uid]!! >= wait || now - (errFirst[uid] ?: now) >= 5L * Settings.hopWait)
                        restart(uid, "error screen ($msg)")
                } else {
                    if ((errSince[uid] ?: 0L) != 0L) say("${names[uid]}: error cleared")
                    errSince[uid] = 0
                    errFirst.remove(uid)
                    if (age >= STALE) {
                        val f = fails[uid] ?: 0
                        if (f >= 3 && l != null && now - l < RETRY) continue   // probably the wrong app: stop closing it over and over
                        if (f == 2) event("\u26A0\uFE0F **${names[uid]}** didn't come back after 3 restarts", true)
                        if (f == 2) say("${names[uid]} didn't come back after 3 restarts. If ${Mapping.get(uid)} is the wrong app, tap the account and pick the right one. Trying again every 15 min.")
                        fails[uid] = f + 1
                        if (l != null) say("${names[uid]} still not back in game; screen in front: ${topAct()}")
                        restart(uid, "no heartbeat for ${age}s (crashed, frozen or not in game)")
                    } else {
                        fails[uid] = 0
                        val mb = Mapping.get(uid)?.let { ram[it] }
                        if (lim > 0 && mb != null) {
                            val h = ramHits[uid] ?: 0
                            if (mb <= lim) {
                                if (l == null || now - l >= RETRY) ramHits.remove(uid)
                            } else if (h < 3 || l == null || now - l >= RETRY) {
                                if (h == 2) event("⚠️ **${names[uid]}** keeps going over the RAM limit ($lim MB). Raise it in settings.", true)
                                ramHits[uid] = h + 1
                                restart(uid, "RAM limit: using $mb MB (limit $lim)")
                            }
                        }
                    }
                }
            }
            WatcherState.accounts = list.sortedBy { it.name.lowercase() }
            WatcherState.pending = pending
            if (queue.isNotEmpty() && now() >= nextLaunch) {
                val (u, why) = queue.removeAt(0)
                doRestart(u, why)
                nextLaunch = now() + Settings.launchGap
            }
        }
        return lastScan to lastClean
    }

    // ------------------------------------------------------------ Discord
    private fun event(text: String, important: Boolean = false) {
        val url = Settings.webhook
        if (!Discord.isWebhook(url) || !Settings.events) return
        val ping = Settings.ping.trim()
        val msg = if (important && ping.isNotEmpty()) "<@$ping> $text" else text
        Thread {
            try { Discord.send(url, msg) } catch (e: Exception) { hookError(e) }
        }.start()
    }

    private fun hookError(e: Exception) {
        val m = e.message ?: e.toString()
        if (m != lastHookError) { lastHookError = m; say("Discord webhook failed: $m") }
    }

    private fun reportLoop() {
        Thread.sleep(20000)
        while (alive) {
            val url = Settings.webhook
            if (Discord.isWebhook(url)) {
                try {
                    Discord.status(url, statusEmbed(), if (Settings.shots && Shell.root) screenshot() else null)
                    lastHookError = ""
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {          // also out-of-memory from the screenshot: keep reporting
                    hookError(Exception(e.message ?: e.toString()))
                }
            }
            Thread.sleep(maxOf(30, Settings.statusEvery) * 1000L)
        }
    }

    private fun statusEmbed(): org.json.JSONObject {
        val accounts = WatcherState.accounts
        val ram = ramByApp()
        val sb = StringBuilder()
        for (a in accounts) {
            val (dot, word) = when {
                a.status == "OK" -> "\uD83D\uDFE2" to "Online"
                a.status.startsWith("loading") || a.status.startsWith("waiting") || a.status == "restarting" ->
                    "\uD83D\uDFE1" to a.status.replaceFirstChar { it.uppercase() }
                a.status == "not matched" -> "\u26AB" to "Not matched"
                else -> "\uD83D\uDD34" to a.status.replaceFirstChar { it.uppercase() }
            }
            val mb = a.pkg?.let { ram[it] }
            sb.append("$dot **${a.name}** \u2014 $word\n")
            sb.append("Game: ${gameName(Settings.placeFor(a.uid))} \u00B7 Rejoins: ${a.restarts} \u00B7 RAM: ${mb?.let { "$it MB" } ?: "\u2013"}\n")
            if (a.lastReason.isNotEmpty()) sb.append("Last restart: ${a.lastReason}\n")
            sb.append("\n")
        }
        if (accounts.isEmpty()) sb.append("No accounts seen yet.")
        else sb.append("All Roblox apps: ${"%.1f".format(ram.values.sum() / 1024.0)} GB RAM")
        val online = accounts.count { it.status == "OK" }
        val (cpu, used, total) = systemStats()
        val embed = org.json.JSONObject()
            .put("title", "NovaHop Rejoin \u00B7 $online/${accounts.size} online")
            .put("description", sb.toString().take(3900))
            .put("color", 0x7C3AED)
            .put("timestamp", Discord.nowIso())
            .put("footer", org.json.JSONObject().put("text", "Updates every ${maxOf(30, Settings.statusEvery)}s"))
        val sys = if (total > 0) "CPU ${cpu}% \u00B7 RAM ${"%.1f".format(used / 1024.0)} / ${"%.1f".format(total / 1024.0)} GB" else "unavailable"
        embed.put("fields", org.json.JSONArray().put(org.json.JSONObject().put("name", "Device").put("value", sys).put("inline", false)))
        if (Settings.shots && Shell.root) embed.put("image", org.json.JSONObject().put("url", "attachment://screen.jpg"))
        return embed
    }

    // RAM per Roblox app in MB, from each app's main process
    private fun ramByApp(): Map<String, Long> {
        val cmd = apps.joinToString("; ") {
            "p=\$(pidof -s $it); [ -n \"\$p\" ] && echo \"$it \$(grep VmRSS /proc/\$p/status)\""
        }
        val out = HashMap<String, Long>()
        for (l in Shell.run(cmd).lines()) {
            val m = Regex("^(\\S+) VmRSS:\\s+(\\d+)").find(l.trim()) ?: continue
            out[m.groupValues[1]] = m.groupValues[2].toLong() / 1024
        }
        return out
    }

    // (cpu %, used MB, total MB)
    private fun systemStats(): Triple<Int, Long, Long> {
        var cpu = 0
        val st = Shell.run("head -1 /proc/stat; sleep 1; head -1 /proc/stat").lines().filter { it.startsWith("cpu ") }
        if (st.size == 2) {
            val a = st[0].trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            val b = st[1].trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (a.size >= 4 && b.size >= 4) {
                val idle = (b[3] - a[3]) + (b.getOrElse(4) { 0L } - a.getOrElse(4) { 0L })
                val all = b.sum() - a.sum()
                if (all > 0) cpu = (100 * (all - idle) / all).toInt()
            }
        }
        var total = 0L
        var avail = 0L
        for (l in Shell.run("grep -E 'MemTotal|MemAvailable' /proc/meminfo").lines()) {
            val n = Regex("(\\d+)").find(l)?.value?.toLongOrNull() ?: continue
            if (l.startsWith("MemTotal")) total = n / 1024
            if (l.startsWith("MemAvailable")) avail = n / 1024
        }
        return Triple(cpu, total - avail, total)
    }

    // screen as a small JPEG
    private fun screenshot(): ByteArray? {
        val f = java.io.File(cacheDir, "screen.png")
        Shell.run("screencap -p '${f.absolutePath}' && chmod 666 '${f.absolutePath}'")
        if (!f.exists()) return null
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val w = minOf(1280, bmp.width)
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true)
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        f.delete()
        return out.toByteArray()
    }
}
