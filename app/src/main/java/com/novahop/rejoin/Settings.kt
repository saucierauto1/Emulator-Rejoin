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

    fun link(): String {
        val p = ps
        return when {
            p.startsWith("linkCode=") -> "roblox://placeId=$place&$p"
            p.startsWith("share=") -> "roblox://navigation/share_links?code=${p.removePrefix("share=")}&type=Server"
            else -> "roblox://placeId=$place"
        }
    }
}
