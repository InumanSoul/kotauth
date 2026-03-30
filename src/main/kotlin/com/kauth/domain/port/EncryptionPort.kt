package com.kauth.domain.port

interface EncryptionPort {
    fun encrypt(plaintext: String): String

    fun decrypt(encrypted: String): String?
}
