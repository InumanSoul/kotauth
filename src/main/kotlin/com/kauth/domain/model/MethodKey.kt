package com.kauth.domain.model

enum class MethodKey {
    PASSWORD,
    PASSKEY,
    MAGIC_LINK,
    EMAIL_OTP,
    SOCIAL_GOOGLE,
    SOCIAL_GITHUB,

    /**
     * Every brokered identity provider, as one row.
     *
     * A provider key is an open string and this enum is closed, so the grid cannot hold one
     * constant per provider — and it is not the page where a provider is switched on anyway.
     */
    EXTERNAL_IDP,
}
