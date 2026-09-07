package com.kauth.domain.model

data class AuthMethodRow(
    val key: MethodKey,
    val labelKey: String,
    val descriptionKey: String?,
    val enabled: Boolean,
    val requirements: List<Requirement>,
    val toggleable: Boolean,
    /** How many providers an aggregate row stands for. Null on a row that is one method. */
    val aggregateCount: Int? = null,
)
