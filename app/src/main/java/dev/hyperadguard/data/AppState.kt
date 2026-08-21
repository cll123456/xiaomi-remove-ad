package dev.hyperadguard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

data class Hit(val domain: String, val timeMillis: Long = System.currentTimeMillis())

object AppState {
    private lateinit var appContext: Context
    private val prefs by lazy { appContext.getSharedPreferences("guard", Context.MODE_PRIVATE) }
    private val counter = AtomicLong(0)
    private val hitQueue = ConcurrentLinkedDeque<Hit>()
    private val _running = MutableStateFlow(false)
    private val _blockedCount = MutableStateFlow(0L)
    private val _recentHits = MutableStateFlow<List<Hit>>(emptyList())

    val running = _running.asStateFlow()
    val blockedCount = _blockedCount.asStateFlow()
    val recentHits = _recentHits.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val saved = prefs.getLong("blocked_count", 0L)
        counter.set(saved)
        _blockedCount.value = saved
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setDesiredEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
    }

    fun wasEnabled(): Boolean = prefs.getBoolean("enabled", false)

    fun recordBlocked(domain: String) {
        val count = counter.incrementAndGet()
        hitQueue.addFirst(Hit(domain))
        while (hitQueue.size > 30) hitQueue.pollLast()
        _blockedCount.value = count
        _recentHits.value = hitQueue.toList()
        if (count % 10L == 0L) prefs.edit().putLong("blocked_count", count).apply()
    }

    fun resetStatistics() {
        counter.set(0)
        hitQueue.clear()
        prefs.edit().putLong("blocked_count", 0).apply()
        _blockedCount.value = 0
        _recentHits.value = emptyList()
    }

    fun customRules(): Set<String> = prefs.getStringSet("custom_rules", emptySet()) ?: emptySet()

    fun saveCustomRules(rules: Set<String>) {
        prefs.edit().putStringSet("custom_rules", rules).apply()
    }

    fun bypassPackages(): Set<String> = prefs.getStringSet("bypass_packages", emptySet()) ?: emptySet()

    fun saveBypassPackages(packages: Set<String>) {
        prefs.edit().putStringSet("bypass_packages", packages).apply()
    }

    fun taskDone(id: String): Boolean = prefs.getBoolean("task_$id", false)

    fun setTaskDone(id: String, done: Boolean) {
        prefs.edit().putBoolean("task_$id", done).apply()
    }
}
