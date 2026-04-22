package com.kauth.adapter.web.plugin

fun buildCspPolicy(formActionOrigins: Set<String> = emptySet()): String {
    val formAction =
        if (formActionOrigins.isEmpty()) {
            "'self'"
        } else {
            "'self' " + formActionOrigins.joinToString(" ")
        }
    return listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src 'self' https://fonts.gstatic.com",
        "img-src 'self' data: https:",
        "form-action $formAction",
    ).joinToString("; ")
}
