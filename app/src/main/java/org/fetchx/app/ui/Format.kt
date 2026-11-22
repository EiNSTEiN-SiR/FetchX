package org.fetchx.app.ui

import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${b} B" else String.format(Locale.ROOT, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun eta(sec: Long): String {
        if (sec < 0) return "--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
