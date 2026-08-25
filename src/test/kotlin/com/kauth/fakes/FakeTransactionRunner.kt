package com.kauth.fakes

import com.kauth.domain.port.TransactionRunner

/**
 * Test fake — executes the block synchronously, and rolls every registered [SnapshotableFake] back
 * to its pre-block state when the block throws, which is the contract [TransactionRunner]
 * documents ("rollback on throw").
 *
 * Register the fakes a transaction writes through: `FakeTransactionRunner(groupRepo)`. With none
 * registered this is a plain pass-through, which is all a test needs when the code under test
 * validates before it writes. Register them whenever the boundary itself is the thing under test —
 * a route that writes metadata and only then rejects a member has nothing else asserting that the
 * metadata write went away.
 *
 * Call-recording lists on a fake (`deleteCalls`, `addUserToGroupCalls`, …) are deliberately NOT
 * rolled back: they record calls that really were made, and a test asserting "the refused write
 * never reached the repository" must still be able to see one that did.
 */
class FakeTransactionRunner(
    private vararg val participants: SnapshotableFake,
) : TransactionRunner {
    var invocations: Int = 0
        private set

    companion object {
        /**
         * A runner that deliberately provides no rollback boundary, for tests where the code under
         * test validates before it writes. Named rather than left to the argument-less constructor
         * so that a test which needs a real boundary cannot get a pass-through by omission.
         */
        fun passThrough(): FakeTransactionRunner = FakeTransactionRunner()
    }

    override fun <T> runInTransaction(block: () -> T): T {
        invocations++
        val snapshots = participants.map { it.snapshot() }
        return try {
            block()
        } catch (e: Throwable) {
            snapshots.forEach { it.restore() }
            throw e
        }
    }
}
