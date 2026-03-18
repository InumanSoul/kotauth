package com.kauth.fakes

import com.kauth.domain.port.PasswordHasher

/**
 * Deterministic PasswordHasher for unit tests.
 *
 * Stores passwords as "hashed:{raw}" instead of running BCrypt.
 * BCrypt is deliberately excluded from unit tests because:
 *   - It is intentionally slow (~100ms per operation)
 *   - A suite of 60 tests would take ~6 seconds just waiting for hashing
 *   - The correctness of BCrypt itself is not what we are testing
 *
 * Security note: NEVER use this outside of tests.
 */
class FakePasswordHasher : PasswordHasher {
    override fun hash(rawPassword: String): String = "hashed:$rawPassword"

    override fun verify(
        rawPassword: String,
        hashedPassword: String,
    ): Boolean = hashedPassword == "hashed:$rawPassword"
}
