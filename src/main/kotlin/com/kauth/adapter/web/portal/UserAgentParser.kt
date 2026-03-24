package com.kauth.adapter.web.portal

object UserAgentParser {
    fun parse(ua: String?): String {
        if (ua.isNullOrBlank()) return "Unknown device"
        val browser = detectBrowser(ua)
        val os = detectOs(ua)
        return if (os != null) "$browser on $os" else browser
    }

    private fun detectBrowser(ua: String): String =
        when {
            ua.contains("Edg/", ignoreCase = true) -> "Edge"
            ua.contains("OPR/", ignoreCase = true) || ua.contains("Opera", ignoreCase = true) -> "Opera"
            ua.contains("Brave", ignoreCase = true) -> "Brave"
            ua.contains("Vivaldi", ignoreCase = true) -> "Vivaldi"
            ua.contains("Chrome/", ignoreCase = true) && ua.contains("Safari/", ignoreCase = true) -> "Chrome"
            ua.contains("Firefox/", ignoreCase = true) -> "Firefox"
            ua.contains("Safari/", ignoreCase = true) && !ua.contains("Chrome", ignoreCase = true) -> "Safari"
            ua.contains("MSIE", ignoreCase = true) || ua.contains("Trident/", ignoreCase = true) -> "Internet Explorer"
            else -> "Unknown browser"
        }

    private fun detectOs(ua: String): String? =
        when {
            ua.contains("iPhone", ignoreCase = true) -> "iPhone"
            ua.contains("iPad", ignoreCase = true) -> "iPad"
            ua.contains("Android", ignoreCase = true) -> "Android"
            ua.contains("Macintosh", ignoreCase = true) || ua.contains("Mac OS", ignoreCase = true) -> "macOS"
            ua.contains("Windows", ignoreCase = true) -> "Windows"
            ua.contains("Linux", ignoreCase = true) -> "Linux"
            ua.contains("CrOS", ignoreCase = true) -> "ChromeOS"
            else -> null
        }
}
