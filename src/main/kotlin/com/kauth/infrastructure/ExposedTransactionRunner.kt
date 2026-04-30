package com.kauth.infrastructure

import com.kauth.domain.port.TransactionRunner
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Production [TransactionRunner] — wraps Exposed's `transaction { }`.
 *
 * Repository methods inside the block already open their own `transaction { }`
 * scopes, but Exposed reuses the outer transaction context when one is already
 * active, so they all participate in this single rollback boundary. Throw
 * anywhere inside the block and every write rolls back.
 */
class ExposedTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transaction { block() }
}
