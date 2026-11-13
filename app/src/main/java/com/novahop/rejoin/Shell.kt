package com.novahop.rejoin

import java.util.concurrent.TimeUnit

object Shell {
    @Volatile
    var root = false
        private set

    fun checkRoot(): Boolean {
        root = run("id", forceRoot = true).contains("uid=0")
        return root
    }

    fun run(cmd: String, forceRoot: Boolean = false): String {
        val full = "( $cmd ) 2>/dev/null"
        val args = if (forceRoot || root) listOf("su", "-c", full) else listOf("sh", "-c", full)
        return try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            ""
        }
    }
}
