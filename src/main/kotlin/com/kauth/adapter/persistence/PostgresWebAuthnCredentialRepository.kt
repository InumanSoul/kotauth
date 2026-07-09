package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.port.WebAuthnCredentialRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset

class PostgresWebAuthnCredentialRepository(
    private val json: Json = Json,
) : WebAuthnCredentialRepository {
    private val transportsSerializer = ListSerializer(String.serializer())

    override fun save(credential: WebAuthnCredential): WebAuthnCredential =
        transaction {
            if (credential.id == null) {
                val insertedId =
                    WebAuthnCredentialsTable.insert {
                        it[userId] = credential.userId.value
                        it[tenantId] = credential.tenantId.value
                        it[credentialId] = credential.credentialId
                        it[publicKeyCose] = credential.publicKeyCose
                        it[signCounter] = credential.signCounter
                        it[aaguid] = credential.aaguid?.toString()
                        it[transports] = json.encodeToString(transportsSerializer, credential.transports)
                        it[name] = credential.name
                        it[backupEligible] = credential.backupEligible
                        it[backupState] = credential.backupState
                        it[createdAt] = credential.createdAt.atOffset(ZoneOffset.UTC)
                        it[lastUsedAt] = credential.lastUsedAt?.atOffset(ZoneOffset.UTC)
                    } get WebAuthnCredentialsTable.id
                credential.copy(id = insertedId)
            } else {
                WebAuthnCredentialsTable.update({ WebAuthnCredentialsTable.id eq credential.id }) {
                    it[signCounter] = credential.signCounter
                    it[name] = credential.name
                    it[lastUsedAt] = credential.lastUsedAt?.atOffset(ZoneOffset.UTC)
                }
                credential
            }
        }

    override fun findById(id: Long): WebAuthnCredential? =
        transaction {
            WebAuthnCredentialsTable
                .selectAll()
                .where { WebAuthnCredentialsTable.id eq id }
                .singleOrNull()
                ?.toCredential()
        }

    override fun findByCredentialId(credentialId: String): WebAuthnCredential? =
        transaction {
            WebAuthnCredentialsTable
                .selectAll()
                .where { WebAuthnCredentialsTable.credentialId eq credentialId }
                .singleOrNull()
                ?.toCredential()
        }

    override fun findByUserId(
        userId: UserId,
        tenantId: TenantId,
    ): List<WebAuthnCredential> =
        transaction {
            WebAuthnCredentialsTable
                .selectAll()
                .where {
                    (WebAuthnCredentialsTable.userId eq userId.value) and
                        (WebAuthnCredentialsTable.tenantId eq tenantId.value)
                }.orderBy(WebAuthnCredentialsTable.createdAt)
                .map { it.toCredential() }
        }

    override fun updateCounter(
        id: Long,
        signCounter: Long,
        lastUsedAt: Instant,
    ) {
        transaction {
            WebAuthnCredentialsTable.update({ WebAuthnCredentialsTable.id eq id }) {
                it[WebAuthnCredentialsTable.signCounter] = signCounter
                it[WebAuthnCredentialsTable.lastUsedAt] = lastUsedAt.atOffset(ZoneOffset.UTC)
            }
        }
    }

    override fun rename(
        id: Long,
        userId: UserId,
        newName: String,
    ): Boolean =
        transaction {
            val rows =
                WebAuthnCredentialsTable.update({
                    (WebAuthnCredentialsTable.id eq id) and
                        (WebAuthnCredentialsTable.userId eq userId.value)
                }) {
                    it[name] = newName
                }
            rows > 0
        }

    override fun delete(
        id: Long,
        userId: UserId,
    ): Boolean =
        transaction {
            val rows =
                WebAuthnCredentialsTable.deleteWhere {
                    (WebAuthnCredentialsTable.id eq id) and (WebAuthnCredentialsTable.userId eq userId.value)
                }
            rows > 0
        }

    override fun deleteAllByUserId(
        userId: UserId,
        tenantId: TenantId,
    ): Int =
        transaction {
            WebAuthnCredentialsTable.deleteWhere {
                (WebAuthnCredentialsTable.userId eq userId.value) and
                    (WebAuthnCredentialsTable.tenantId eq tenantId.value)
            }
        }

    override fun countEnrolledUsersByTenantId(tenantId: TenantId): Int =
        transaction {
            WebAuthnCredentialsTable
                .select(WebAuthnCredentialsTable.userId)
                .where { WebAuthnCredentialsTable.tenantId eq tenantId.value }
                .withDistinct()
                .count()
                .toInt()
        }

    private fun ResultRow.toCredential(): WebAuthnCredential {
        val transportsJson = this[WebAuthnCredentialsTable.transports]
        val transportsList =
            if (transportsJson.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString(transportsSerializer, transportsJson)
            }
        return WebAuthnCredential(
            id = this[WebAuthnCredentialsTable.id],
            userId = UserId(this[WebAuthnCredentialsTable.userId]),
            tenantId = TenantId(this[WebAuthnCredentialsTable.tenantId]),
            credentialId = this[WebAuthnCredentialsTable.credentialId],
            publicKeyCose = this[WebAuthnCredentialsTable.publicKeyCose],
            signCounter = this[WebAuthnCredentialsTable.signCounter],
            aaguid = this[WebAuthnCredentialsTable.aaguid]?.let { java.util.UUID.fromString(it) },
            transports = transportsList,
            name = this[WebAuthnCredentialsTable.name],
            backupEligible = this[WebAuthnCredentialsTable.backupEligible],
            backupState = this[WebAuthnCredentialsTable.backupState],
            createdAt = this[WebAuthnCredentialsTable.createdAt].toInstant(),
            lastUsedAt = this[WebAuthnCredentialsTable.lastUsedAt]?.toInstant(),
        )
    }
}
