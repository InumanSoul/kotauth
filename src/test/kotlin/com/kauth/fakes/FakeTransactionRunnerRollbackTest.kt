package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The rollback boundary itself, asserted directly.
 *
 * The SCIM `/Users` routes wrap a profile write and an enable/disable toggle in one transaction so
 * a failing second leg cannot leave a half-applied write behind. That throw is currently
 * unreachable through HTTP — the toggle fails only when the user does not exist, and the profile
 * write immediately before it proves that it does — so the routes cannot exercise the boundary and
 * a route test asserting it would be theatre. Asserting the mechanism here is the honest version:
 * the runner is wired to the user repository in `ScimUserRoutesTest`, so the day a second leg gains
 * a reachable failure, the boundary is already real rather than newly needed.
 */
class FakeTransactionRunnerRollbackTest {
    private val tenantId = TenantId(1)

    private fun user(username: String) =
        User(
            tenantId = tenantId,
            username = username,
            email = "$username@example.com",
            fullName = username,
            passwordHash = User.SENTINEL_PASSWORD_HASH,
        )

    @Test
    fun `a throwing block puts an inserted user back`() {
        val repo = FakeUserRepository()
        val runner = FakeTransactionRunner(repo)

        assertFailsWith<IllegalStateException> {
            runner.runInTransaction {
                repo.add(user("ada"))
                error("second leg failed")
            }
        }

        assertNull(repo.findByUsername(tenantId, "ada"))
    }

    @Test
    fun `a throwing block puts an updated user back`() {
        val repo = FakeUserRepository()
        val existing = repo.add(user("ada"))
        val runner = FakeTransactionRunner(repo)

        assertFailsWith<IllegalStateException> {
            runner.runInTransaction {
                repo.update(existing.copy(fullName = "Renamed"))
                error("second leg failed")
            }
        }

        assertEquals("ada", repo.findById(existing.id!!, tenantId)!!.fullName)
    }

    @Test
    fun `a successful block keeps its writes`() {
        val repo = FakeUserRepository()
        val runner = FakeTransactionRunner(repo)

        runner.runInTransaction { repo.add(user("ada")) }

        assertEquals("ada", repo.findByUsername(tenantId, "ada")?.username)
    }

    @Test
    fun `a pass-through runner rolls nothing back`() {
        val repo = FakeUserRepository()
        val runner = FakeTransactionRunner.passThrough()

        assertFailsWith<IllegalStateException> {
            runner.runInTransaction {
                repo.add(user("ada"))
                error("second leg failed")
            }
        }

        // Not a bug — this is what the argument-less runner is for, and naming it is what stops a
        // test from getting this behaviour by accident when it meant to assert a boundary.
        assertEquals("ada", repo.findByUsername(tenantId, "ada")?.username)
    }
}
