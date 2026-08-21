package com.kauth.adapter.web.auth

import com.kauth.domain.service.OAuthError
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthHelpersTest {
    @Test
    fun `UnauthorizedClient maps to the unauthorized_client wire error code`() {
        val error = OAuthError.UnauthorizedClient("Client is not registered for the client_credentials grant")

        assertEquals("unauthorized_client", error.toErrorCode())
        assertEquals("Client is not registered for the client_credentials grant", error.toDescription())
    }
}
