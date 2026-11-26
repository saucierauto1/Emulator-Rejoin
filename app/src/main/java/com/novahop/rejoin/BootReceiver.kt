package com.novahop.rejoin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Starts the watcher when the emulator/phone starts: always if "start when the emulator starts" is on,
// otherwise only if it was running before the restart.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Settings.init(ctx)
        if (Settings.autoBoot || ctx.getSharedPreferences("state", Context.MODE_PRIVATE).getBoolean("on", false)) {
            try { WatcherService.start(ctx) } catch (e: Exception) { }
        }
    }
}
