package com.novahop.rejoin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Starts the watcher again after a reboot if it was running before.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ctx.getSharedPreferences("state", Context.MODE_PRIVATE).getBoolean("on", false)) {
            try { WatcherService.start(ctx) } catch (e: Exception) { }
        }
    }
}
