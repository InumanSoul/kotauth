package com.kauth.domain.model

/**
 * Outcome of a TOTP verification. [matchedStep] is the RFC 6238 time step the code
 * matched against (within the ±1 clock-skew window); callers compare it against the
 * enrollment's last accepted step to reject replays.
 */
data class TotpVerifyResult(
    val valid: Boolean,
    val matchedStep: Long? = null,
) {
    companion object {
        val INVALID = TotpVerifyResult(valid = false, matchedStep = null)
    }
}
