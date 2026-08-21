package dev.hyperadguard.vpn

import android.content.Context
import dev.hyperadguard.data.AppState
import java.util.Locale

class DomainBlocker(context: Context) {
    private val exact = HashSet<String>()
    private val suffixes = HashSet<String>()

    init {
        context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            lines.forEach(::addRule)
        }
        AppState.customRules().forEach(::addRule)
    }

    private fun addRule(raw: String) {
        val rule = raw.substringBefore('#').trim().lowercase(Locale.US).trimEnd('.')
        if (rule.isBlank()) return
        if (rule.startsWith("*.")) suffixes += rule.removePrefix("*.") else exact += rule
    }

    fun blocks(rawDomain: String): Boolean {
        val domain = rawDomain.lowercase(Locale.US).trimEnd('.')
        if (domain in exact) return true
        var dot = domain.indexOf('.')
        while (dot >= 0 && dot < domain.lastIndex) {
            if (domain.substring(dot + 1) in suffixes) return true
            dot = domain.indexOf('.', dot + 1)
        }
        return domain in suffixes
    }
}
