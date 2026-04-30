package com.kauth.domain.port

/**
 * Port (outbound) — wraps a block of repository calls in a single database
 * transaction so all writes commit together or all roll back together.
 *
 * Domain services that perform multi-step writes (e.g., tenant import) inject
 * this port instead of importing Exposed directly. The Exposed adapter wires
 * it to `transaction { block() }`; in-memory test fakes just invoke the block.
 *
 * Implementations must propagate exceptions from [block] up to the caller —
 * the caller decides whether to map them to typed errors. Implementations must
 * NOT swallow or wrap exceptions; the only contract is "rollback on throw."
 */
interface TransactionRunner {
    fun <T> runInTransaction(block: () -> T): T
}
