package com.kauth.domain.port

interface EncryptionPort {
    val isAvailable: Boolean

    fun encrypt(plaintext: String): String

    fun decrypt(encrypted: String): String?
}
