package com.kauth.domain.model

/**
 * How a sign-in was proven, as RFC 8176 `amr` values, and the OIDC `acr` it earns.
 *
 * Values: `pwd` password, `otp` a one-time code (authenticator app, recovery code, email code or
 * magic link), `fed` a social or OIDC provider, `hwk` / `swk` a device-bound / synced passkey,
 * `mfa` more than one factor. `fed` is not in the RFC 8176 registry; it is the value Microsoft
 * Entra and others use for federated sign-in.
 */
object AuthMethods {
    const val PASSWORD = "pwd"
    const val ONE_TIME_CODE = "otp"
    const val FEDERATED = "fed"
    const val HARDWARE_KEY = "hwk"
    const val SOFTWARE_KEY = "swk"
    const val MULTI_FACTOR = "mfa"

    /** OpenID PAPE multi-factor policy: the `acr` of a multi-factor sign-in, and the `acr_values` that asks for one. */
    const val ACR_MULTI_FACTOR = "http://schemas.openid.net/pape/policies/2007/06/multi-factor"

    /**
     * A passkey's `amr`: `swk` when it can sync between devices (backup eligible), `hwk` when it is
     * bound to one device, plus `mfa` with user verification, which proves a second factor (ADR-16).
     */
    fun forPasskey(
        synced: Boolean,
        userVerified: Boolean,
    ): List<String> = listOfNotNull(if (synced) SOFTWARE_KEY else HARDWARE_KEY, MULTI_FACTOR.takeIf { userVerified })

    /** The `amr` of a first factor completed by a second one (authenticator code or recovery code). */
    fun withSecondFactor(firstFactor: List<String>): List<String> =
        (firstFactor - MULTI_FACTOR + ONE_TIME_CODE + MULTI_FACTOR).distinct()

    fun isMultiFactor(amr: List<String>): Boolean = MULTI_FACTOR in amr

    /** The `acr` an `amr` earns, or null for a single-factor sign-in. */
    fun acrFor(amr: List<String>): String? = if (isMultiFactor(amr)) ACR_MULTI_FACTOR else null

    /** Whether an OIDC `acr_values` request asks for a multi-factor sign-in. */
    fun requestsMultiFactor(acrValues: String?): Boolean = acrValues?.split(' ')?.any { it == ACR_MULTI_FACTOR } == true
}
