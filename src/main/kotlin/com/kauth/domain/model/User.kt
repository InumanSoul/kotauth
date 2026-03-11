package com.kauth.domain.model

/**
 * Core domain entity representing an authenticated user.
 * This class has zero dependencies on any framework (Ktor, Exposed, etc.).
 * It lives at the center of the hexagon and is the source of truth for what a User IS.
 */
data class User(
    val id: Int? = null,
    val username: String,
    val email: String,
    val fullName: String,
    val passwordHash: String
)
