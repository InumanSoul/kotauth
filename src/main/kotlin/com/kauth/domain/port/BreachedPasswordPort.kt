package com.kauth.domain.port

/**
 * Port (outbound) — checks whether a raw password appears in a known breach corpus.
 *
 * Implementations MUST use k-Anonymity or an equivalent privacy-preserving protocol;
 * the full password or its complete hash must never leave the process.
 *
 * Implementations MUST NOT throw — callers interpret any failure as "allow"
 * (fail-open). A breach-detection service going down should not block
 * registrations or password changes.
 */
interface BreachedPasswordPort {
    /**
     * @return true if [rawPassword] appears in a known breach, false otherwise
     *   or on any error (fail-open).
     */
    fun isBreached(rawPassword: String): Boolean
}
