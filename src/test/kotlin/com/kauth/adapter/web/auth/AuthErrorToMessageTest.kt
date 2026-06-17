package com.kauth.adapter.web.auth

import com.kauth.domain.service.AuthError
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks down M6: error states that would otherwise let an unauthenticated caller
 * enumerate accounts (locked / expired / pending invite / forced change) must
 * render the same generic message as a wrong-password attempt.
 */
class AuthErrorToMessageTest {
    private val generic = "Invalid username or password."

    @Test
    fun `InvalidCredentials renders the generic message`() {
        assertEquals(generic, AuthError.InvalidCredentials.toMessage())
    }

    @Test
    fun `AccountLocked renders the same generic message`() {
        assertEquals(generic, AuthError.AccountLocked(Instant.now().plusSeconds(900)).toMessage())
    }

    @Test
    fun `PasswordExpired renders the same generic message`() {
        assertEquals(generic, AuthError.PasswordExpired.toMessage())
    }

    @Test
    fun `PendingSetup renders the same generic message`() {
        assertEquals(generic, AuthError.PendingSetup.toMessage())
    }

    @Test
    fun `PasswordChangeRequired renders the same generic message`() {
        assertEquals(generic, AuthError.PasswordChangeRequired.toMessage())
    }
}
