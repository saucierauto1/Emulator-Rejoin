package com.novahop.rejoin

import android.content.Context
import android.content.SharedPreferences

// Which game to rejoin and an optional private server. Replaces ~/.novahop_conf.
object Settings {
    const val BEE_SWARM = "1537690962"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("novahop_conf", Context.MODE_PRIVATE)
    }

    val place: String get() = prefs?.getString("place", BEE_SWARM) ?: BEE_SWARM
    val ps: String get() = prefs?.getString("ps", "") ?: ""         // "linkCode=..." or "share=..." or ""
    val psRaw: String get() = prefs?.getString("psRaw", "") ?: ""   // the link as the user pasted it

    // gap between launching clones, Discord webhook and its live status message
    val launchGap: Int get() = prefs?.getInt("gap", 15) ?: 15
    val webhook: String get() = prefs?.getString("webhook", "") ?: ""
    val ping: String get() = prefs?.getString("ping", "") ?: ""
    val statusEvery: Int get() = prefs?.getInt("every", 60) ?: 60
    val shots: Boolean get() = prefs?.getBoolean("shots", true) ?: true
    val events: Boolean get() = prefs?.getBoolean("events", true) ?: true
    val errorWait: Int get() = prefs?.getInt("errWait", 20) ?: 20     // normal error screens (279, kicks)
    val hopWait: Int get() = prefs?.getInt("hopWait", 90) ?: 90       // teleport / full server: NovaHop retries itself

    val autoBoot: Boolean get() = prefs?.getBoolean("autoBoot", false) ?: false   // start when the emulator starts
    val openAll: Boolean get() = prefs?.getBoolean("openAll", true) ?: true       // START opens every clone
    val cacheHours: Int get() = prefs?.getInt("cacheHours", 0) ?: 0                // clear Roblox cache every N hours, 0 = off

    val ramLimit: Int get() = prefs?.getInt("ramLimit", 0) ?: 0   // restart a clone using more MB than this, 0 = off
    val fpsCap: Int get() = prefs?.getInt("fpsCap", 0) ?: 0       // setfpscap in the executor's autoexec, 0 = off

    fun savePerf(ramLimit: Int, fpsCap: Int) {
        prefs?.edit()?.putInt("ramLimit", ramLimit)?.putInt("fpsCap", fpsCap)?.apply()
    }

    // a clone's own game / private server, per account (empty place = uses the main one)
    fun ownPlace(uid: String): String = prefs?.getString("place_$uid", "") ?: ""
    fun ownPsRaw(uid: String): String = prefs?.getString("psRaw_$uid", "") ?: ""
    private fun ownPs(uid: String): String = prefs?.getString("ps_$uid", "") ?: ""

    fun saveOwn(uid: String, place: String, ps: String, psRaw: String) {
        prefs?.edit()?.putString("place_$uid", place)?.putString("ps_$uid", ps)?.putString("psRaw_$uid", psRaw)?.apply()
    }

    fun placeFor(uid: String?): String = if (uid != null && ownPlace(uid).isNotEmpty()) ownPlace(uid) else place

    fun saveMore(autoBoot: Boolean, openAll: Boolean, cacheHours: Int) {
        prefs?.edit()?.putBoolean("autoBoot", autoBoot)?.putBoolean("openAll", openAll)
            ?.putInt("cacheHours", cacheHours)?.apply()
    }

    fun saveWaits(errorWait: Int, hopWait: Int) {
        prefs?.edit()?.putInt("errWait", errorWait)?.putInt("hopWait", hopWait)?.apply()
    }
    var statusMsg: String
        get() = prefs?.getString("msg", "") ?: ""
        set(v) { prefs?.edit()?.putString("msg", v)?.apply() }

    fun saveExtra(gap: Int, webhook: String, ping: String, every: Int, shots: Boolean, events: Boolean) {
        prefs?.edit()?.putInt("gap", gap)?.putString("webhook", webhook)?.putString("ping", ping)
            ?.putInt("every", every)?.putBoolean("shots", shots)?.putBoolean("events", events)?.apply()
    }

    fun save(place: String, ps: String, psRaw: String) {
        prefs?.edit()?.putString("place", place)?.putString("ps", ps)?.putString("psRaw", psRaw)?.apply()
    }

    // game link or place ID -> place ID, null if not understood
    fun parseGame(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return BEE_SWARM
        Regex("games/([0-9]+)").find(t)?.let { return it.groupValues[1] }
        return if (t.all { it.isDigit() }) t else null
    }

    // private server link -> "linkCode=..." / "share=...", "" for none, null if not understood
    fun parsePs(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return ""
        Regex("privateServerLinkCode=([A-Za-z0-9_-]+)").find(t)?.let { return "linkCode=" + it.groupValues[1] }
        Regex("code=([A-Za-z0-9]+)").find(t)?.let { return "share=" + it.groupValues[1] }
        return null
    }

    fun link(uid: String? = null): String {
        val own = uid != null && ownPlace(uid).isNotEmpty()
        val pl = if (own) ownPlace(uid!!) else place
        val p = if (own) ownPs(uid!!) else ps
        return when {
            p.startsWith("linkCode=") -> "roblox://placeId=$pl&$p"
            p.startsWith("share=") -> "roblox://navigation/share_links?code=${p.removePrefix("share=")}&type=Server"
            else -> "roblox://placeId=$pl"
        }
    }
}
