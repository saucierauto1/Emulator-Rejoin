package com.novahop.rejoin

import android.content.Context
import android.content.SharedPreferences

// Which Roblox app each account (UserId) runs in. Replaces ~/.novahop_map.
object Mapping {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("novahop_map", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun get(uid: String): String? = prefs?.getString(uid, null)

    @Synchronized
    fun all(): Map<String, String> =
        prefs?.all?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()

    fun taken(pkg: String) = all().containsValue(pkg)

    @Synchronized
    fun set(uid: String, pkg: String) {
        val e = prefs?.edit() ?: return
        all().filter { it.value == pkg && it.key != uid }.keys.forEach { e.remove(it) }
        e.putString(uid, pkg).apply()
    }

    @Synchronized
    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    // People coming from the Termux watcher keep their matches
    fun importTermux(): Int {
        if (all().isNotEmpty()) return 0
        var n = 0
        Shell.run("cat /data/data/com.termux/files/home/.novahop_map").lines().forEach { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size == 2 && p[0].isNotEmpty() && p[0].all { it.isDigit() }) {
                set(p[0], p[1]); n++
            }
        }
        return n
    }
}
