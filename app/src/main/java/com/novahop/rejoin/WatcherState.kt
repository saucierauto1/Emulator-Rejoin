package com.novahop.rejoin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Account(
    val uid: String,
    val name: String,
    val pkg: String?,
    val state: String,
    val msg: String,
    val age: Long,
)

object WatcherState {
    @Volatile var running = false
    @Volatile var apps: List<String> = emptyList()
    @Volatile var accounts: List<Account> = emptyList()
    @Volatile var pending: Map<String, List<String>> = emptyMap()

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(s: String) {
        lines.addLast("[${fmt.format(Date())}] $s")
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized
    fun logText(): String = lines.joinToString("\n")
}
