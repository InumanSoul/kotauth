package com.kauth.adapter.web.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side one-time flash message store.
 *
 * Secrets and other sensitive values are stored in memory keyed by a random token.
 * The token is safe to put in a redirect URL — it reveals nothing if logged.
 * Values are deleted on first read (one-time use).
 */
object FlashStore {
    private val store = ConcurrentHashMap<String, String>()

    fun put(value: String): String {
        val token = UUID.randomUUID().toString()
        store[token] = value
        return token
    }

    fun take(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return store.remove(token)
    }
}
