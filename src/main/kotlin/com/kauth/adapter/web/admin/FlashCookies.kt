package com.kauth.adapter.web.admin

import com.kauth.infrastructure.EncryptionService
import io.ktor.server.application.ApplicationCall

/**
 * One-time values shown once after a redirect, such as a new client secret or a temporary-password
 * link. Held in an encrypted, short-lived cookie in the admin's own browser rather than in server
 * memory, so the page that shows it can be served by any replica, and an unread value expires on
 * its own.
 *
 * A value is bound to its [purpose] and to the admin who caused it, and read at most once.
 */
class FlashCookies(
    private val encryption: EncryptionService,
    private val secure: Boolean,
) {
    fun put(
        call: ApplicationCall,
        purpose: String,
        admin: String,
        value: String,
    ) {
        val expiresAt = System.currentTimeMillis() + MAX_AGE_SECONDS * 1_000
        call.response.cookies.append(
            name = COOKIE,
            value = encryption.encrypt("$purpose\n$admin\n$expiresAt\n$value"),
            maxAge = MAX_AGE_SECONDS,
            path = PATH,
            httpOnly = true,
            secure = secure,
            extensions = mapOf("SameSite" to "Strict"),
        )
    }

    /** The value stored for [purpose] by [admin], once; null if absent, expired or someone else's. */
    fun take(
        call: ApplicationCall,
        purpose: String,
        admin: String,
    ): String? {
        val raw = call.request.cookies[COOKIE] ?: return null
        val parts = encryption.decrypt(raw)?.split("\n", limit = 4) ?: return null
        if (parts.size != 4 || parts[0] != purpose || parts[1] != admin) return null
        call.response.cookies.append(
            name = COOKIE,
            value = "",
            maxAge = 0L,
            path = PATH,
            httpOnly = true,
            secure = secure,
        )
        val expiresAt = parts[2].toLongOrNull() ?: return null
        return parts[3].takeIf { System.currentTimeMillis() <= expiresAt }
    }

    private companion object {
        const val COOKIE = "KOTAUTH_ADMIN_FLASH"
        const val PATH = "/admin"
        const val MAX_AGE_SECONDS = 120L
    }
}
