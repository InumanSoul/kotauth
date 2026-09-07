package com.kauth.domain.service

import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.TransactionRunner
import com.kauth.domain.port.UserRepository
import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure

/**
 * Reconciles a SCIM-supplied desired membership list against a group's current membership.
 * [GroupRepository] only exposes per-user add/remove, so this service computes the diff —
 * keeping the SCIM route thin and the diffing logic testable without HTTP.
 *
 * [groupId] is assumed already resolved within [tenantId] by the caller (e.g. via
 * `GroupRepository.findByExternalId`); this service scopes only the member ids it is handed.
 */
class ScimGroupMembershipService(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val transactionRunner: TransactionRunner,
) {
    fun reconcile(
        groupId: GroupId,
        tenantId: TenantId,
        desired: List<UserId>,
    ): Result<Unit> {
        val desiredIds = desired.toSet()
        val current = groupRepository.findUserIdsInGroup(groupId).toSet()
        val toAdd = desiredIds - current
        val toRemove = current - desiredIds

        // Validate the whole batch before any write. A cross-tenant member would be a privilege
        // escalation (an IdP in one workspace adding a user from another), and a half-applied
        // membership change is worse than a rejected one because it reports success.
        val validTenantUserIds = userRepository.findByIds(desiredIds, tenantId).mapNotNull { it.id }.toSet()
        for (userId in desiredIds) {
            if (userId !in validTenantUserIds) {
                return Result.failure(
                    ScimFailure(
                        ScimErrorType.invalidValue,
                        "member ${userId.value} does not exist in this workspace",
                    ),
                )
            }
        }

        // Nothing changed: an IdP resends the full membership on every sync, so this is the
        // common case. Skipping the transaction avoids thrashing the database on every poll.
        if (toAdd.isEmpty() && toRemove.isEmpty()) return Result.success(Unit)

        transactionRunner.runInTransaction {
            toAdd.forEach { groupRepository.addUserToGroup(it, groupId) }
            toRemove.forEach { groupRepository.removeUserFromGroup(it, groupId) }
        }

        return Result.success(Unit)
    }
}
