package com.kauth.fakes

import com.kauth.domain.port.TransactionRunner

/**
 * Test fake — executes the block synchronously without any rollback semantics.
 * In-memory fakes don't support snapshot/restore, so importer tests that need
 * to verify rollback should run against a real database in integration tests
 * (Phase 3+).
 *
 * For domain-level tests we instead verify pre-write validation: the importer
 * checks compatibility and slug-collision BEFORE any write, so the validation
 * paths are testable without a real transaction boundary.
 */
class FakeTransactionRunner : TransactionRunner {
    var invocations: Int = 0
        private set

    override fun <T> runInTransaction(block: () -> T): T {
        invocations++
        return block()
    }
}
