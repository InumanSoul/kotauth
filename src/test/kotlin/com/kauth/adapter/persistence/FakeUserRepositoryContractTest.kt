package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.port.UserRepository
import com.kauth.fakes.FakeUserRepository

/** [UserRepositoryContract] against the in-memory fake used by every unit test. */
class FakeUserRepositoryContractTest : UserRepositoryContract() {
    override val repo: UserRepository = FakeUserRepository()

    override fun tenantId(): TenantId = TenantId(1)
}
