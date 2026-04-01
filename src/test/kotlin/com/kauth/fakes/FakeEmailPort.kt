package com.kauth.fakes

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.EmailPort

/**
 * In-memory EmailPort for unit tests.
 * Captures sent emails so tests can assert on delivery.
 * Can be configured to throw to simulate SMTP failures.
 */
class FakeEmailPort : EmailPort {
    data class SentEmail(
        val to: String,
        val toName: String,
        val url: String = "",
        val workspaceName: String,
        val type: String,
    )

    private val _sent = mutableListOf<SentEmail>()
    val sent: List<SentEmail> get() = _sent.toList()

    var shouldFail: Boolean = false

    fun clear() {
        _sent.clear()
        shouldFail = false
    }

    override fun sendVerificationEmail(
        to: String,
        toName: String,
        verifyUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        if (shouldFail) throw RuntimeException("SMTP delivery failed")
        _sent.add(SentEmail(to, toName, verifyUrl, workspaceName, "verification"))
    }

    override fun sendPasswordResetEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        if (shouldFail) throw RuntimeException("SMTP delivery failed")
        _sent.add(SentEmail(to, toName, resetUrl, workspaceName, "password_reset"))
    }

    override fun sendAccountLockedEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        lockoutDuration: String,
        tenant: Tenant,
    ) {
        if (shouldFail) throw RuntimeException("SMTP delivery failed")
        _sent.add(SentEmail(to, toName, resetUrl, workspaceName, "account_locked"))
    }

    override fun sendPasswordChangedEmail(
        to: String,
        toName: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        if (shouldFail) throw RuntimeException("SMTP delivery failed")
        _sent.add(SentEmail(to = to, toName = toName, workspaceName = workspaceName, type = "password_changed"))
    }

    override fun sendTestEmail(
        to: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        if (shouldFail) throw RuntimeException("SMTP delivery failed")
        _sent.add(SentEmail(to = to, toName = to, workspaceName = workspaceName, type = "test"))
    }
}
