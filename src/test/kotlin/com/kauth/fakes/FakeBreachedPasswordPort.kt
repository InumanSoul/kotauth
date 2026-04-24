package com.kauth.fakes

import com.kauth.domain.port.BreachedPasswordPort

/**
 * In-memory [BreachedPasswordPort] for tests.
 *
 * By default: no passwords are breached, no errors thrown.
 * Tests can add specific raw passwords to [breachedPasswords] to simulate
 * a hit, or set [simulateError] to exercise the fail-open path.
 */
class FakeBreachedPasswordPort : BreachedPasswordPort {
    val breachedPasswords = mutableSetOf<String>()
    var callCount = 0
        private set
    var simulateError: Boolean = false

    fun clear() {
        breachedPasswords.clear()
        callCount = 0
        simulateError = false
    }

    override fun isBreached(rawPassword: String): Boolean {
        callCount++
        if (simulateError) {
            // Adapter contract: never throws. Fail-open means return false on error.
            return false
        }
        return rawPassword in breachedPasswords
    }
}
