package com.kauth.domain.service

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScimGroupMembershipServiceTest {
    private val tenantA = TenantId(1)
    private val tenantB = TenantId(2)

    private val groupRepository = FakeGroupRepository()
    private val userRepository = FakeUserRepository()
    private val transactionRunner = FakeTransactionRunner()
    private val service = ScimGroupMembershipService(groupRepository, userRepository, transactionRunner)

    private fun user(
        name: String,
        tenant: TenantId = tenantA,
    ) = userRepository.save(
        User(
            tenantId = tenant,
            username = name,
            email = "$name@example.com",
            fullName = name,
            passwordHash = User.SENTINEL_PASSWORD_HASH,
        ),
    )

    private fun members(groupId: GroupId) = groupRepository.findUserIdsInGroup(groupId).toSet()

    @Test
    fun `adding to an empty group applies every desired member`() {
        val group = groupRepository.save(Group(tenantId = tenantA, name = "engineering"))
        val alice = user("alice").id!!
        val bob = user("bob").id!!

        val result = service.reconcile(group.id!!, tenantA, listOf(alice, bob))

        assertTrue(result.isSuccess)
        assertEquals(setOf(alice, bob), members(group.id!!))
    }

    @Test
    fun `removing every member leaves the group empty`() {
        val group = groupRepository.save(Group(tenantId = tenantA, name = "engineering"))
        val alice = user("alice").id!!
        val bob = user("bob").id!!
        groupRepository.addUserToGroup(alice, group.id!!)
        groupRepository.addUserToGroup(bob, group.id!!)

        val result = service.reconcile(group.id!!, tenantA, emptyList())

        assertTrue(result.isSuccess)
        assertEquals(emptySet(), members(group.id!!))
    }

    @Test
    fun `a mixed add and remove leaves exactly the desired set`() {
        val group = groupRepository.save(Group(tenantId = tenantA, name = "engineering"))
        val alice = user("alice").id!!
        val bob = user("bob").id!!
        val carol = user("carol").id!!
        groupRepository.addUserToGroup(alice, group.id!!)
        groupRepository.addUserToGroup(bob, group.id!!)

        // Desired: drop alice, keep bob, add carol.
        val result = service.reconcile(group.id!!, tenantA, listOf(bob, carol))

        assertTrue(result.isSuccess)
        assertEquals(setOf(bob, carol), members(group.id!!))
    }

    @Test
    fun `an unchanged reconcile issues zero add or remove calls`() {
        val group = groupRepository.save(Group(tenantId = tenantA, name = "engineering"))
        val alice = user("alice").id!!
        val bob = user("bob").id!!
        groupRepository.addUserToGroup(alice, group.id!!)
        groupRepository.addUserToGroup(bob, group.id!!)
        groupRepository.addUserToGroupCalls.clear()

        val result = service.reconcile(group.id!!, tenantA, listOf(alice, bob))

        assertTrue(result.isSuccess)
        assertEquals(setOf(alice, bob), members(group.id!!))
        // A set-equality check on membership alone would pass whether or not writes happened —
        // the counting fake is the only way to catch a no-op that rewrites every row anyway.
        assertEquals(0, groupRepository.addUserToGroupCalls.size)
        assertEquals(0, groupRepository.removeUserFromGroupCalls.size)
    }

    @Test
    fun `a non-existent member id fails with invalidValue and leaves membership unchanged`() {
        val group = groupRepository.save(Group(tenantId = tenantA, name = "engineering"))
        val alice = user("alice").id!!
        groupRepository.addUserToGroup(alice, group.id!!)
        val bogus = UserId(999_999)

        val result = service.reconcile(group.id!!, tenantA, listOf(alice, bogus))

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as ScimFailure
        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains(bogus.value.toString()))
        assertEquals(setOf(alice), members(group.id!!))
        assertEquals(0, groupRepository.removeUserFromGroupCalls.size)
    }

    @Test
    fun `a member from another tenant is rejected and membership is unchanged`() {
        val group = groupRepository.save(Group(tenantId = tenantA, name = "engineering"))
        val alice = user("alice", tenantA).id!!
        val outsider = user("mallory", tenantB).id!!
        groupRepository.addUserToGroup(alice, group.id!!)
        groupRepository.addUserToGroupCalls.clear()

        val result = service.reconcile(group.id!!, tenantA, listOf(alice, outsider))

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as ScimFailure
        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertEquals(setOf(alice), members(group.id!!))
        assertEquals(0, groupRepository.addUserToGroupCalls.size)
        assertEquals(0, groupRepository.removeUserFromGroupCalls.size)
    }
}
