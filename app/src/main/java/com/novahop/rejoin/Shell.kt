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
            var out = ""
            // read on the side, so a command that hangs (root prompt not answered, dumpsys stuck) can't freeze the watcher
            val reader = Thread { out = try { p.inputStream.bufferedReader().readText() } catch (e: Exception) { "" } }
            reader.start()
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
            reader.join(5000)
            out
        } catch (e: Exception) {
            ""
        }
    }
}
