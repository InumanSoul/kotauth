package com.kauth.fakes

/**
 * An in-memory fake whose state can be captured and put back, so [FakeTransactionRunner] can give
 * the suite a real rollback boundary.
 *
 * The alternative — "verify rollback against a real database in an integration test" — is not
 * available here: this suite runs with no Docker and no database, so deferring the assertion means
 * never making it, and the routes whose whole purpose is that a rejected member reverts the
 * metadata write end up asserted by nothing. A fake's state is plain maps; copying them is enough.
 */
interface SnapshotableFake {
    /** Captures this fake's state as it is right now. */
    fun snapshot(): FakeRestore
}

/** Puts one fake's state back the way it was when the snapshot was taken. */
fun interface FakeRestore {
    fun restore()
}
