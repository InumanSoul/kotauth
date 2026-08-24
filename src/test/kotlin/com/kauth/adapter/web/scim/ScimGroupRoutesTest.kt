package com.kauth.adapter.web.scim

import com.kauth.adapter.web.api.AlwaysAllowLimiter
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.api.stubEmailOtpService
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeMfaRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration tests for `/Groups`, mounted at `/t/{tenantSlug}/scim/v2/Groups`.
 */
class ScimGroupRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()
    private val userAttributeRepo = FakeUserAttributeRepository()
    private val claimMapperRepo = FakeTenantClaimMapperRepository()
    private val mfaRepo = FakeMfaRepository()
    private val hasher = FakePasswordHasher()
    private val transactionRunner = FakeTransactionRunner()

    private val acme =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = "https://acme.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
        )

    private val globex =
        Tenant(
            id = TenantId(2),
            slug = "globex",
            displayName = "Globex Corp",
            issuerUrl = "https://globex.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
        )

    private val apiKeyService = ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo)

    private val accountSelfService =
        CredentialFlowService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            evTokenRepo = evTokenRepo,
            prTokenRepo = prTokenRepo,
            emailPort = emailPort,
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val accountService =
        AdminAccountService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            auditLog = auditLogPort,
            credentialFlowService = accountSelfService,
        )

    private val adminUserService =
        com.kauth.domain.service.AdminUserService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            credentialFlowService = accountSelfService,
        )

    private val mfaService =
        MfaService(
            mfaRepository = mfaRepo,
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
        )

    private val applicationManagementService =
        com.kauth.domain.service.ApplicationManagementService(
            applicationRepository = appRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
        )

    private val roleGroupService =
        RoleGroupService(
            roleRepository = roleRepo,
            groupRepository = groupRepo,
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            auditLog = auditLogPort,
        )

    private val userAttributeService =
        com.kauth.domain.service.UserAttributeService(
            userAttributeRepository = userAttributeRepo,
            userRepository = userRepo,
        )

    private val claimMapperService = CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private val webhookService =
        WebhookService(
            endpointRepository = FakeWebhookEndpointRepository(),
            deliveryRepository = FakeWebhookDeliveryRepository(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private var scimKey: String = ""
    private var noScimKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        groupRepo.clear()
        apiKeyRepo.clear()
        auditLogRepo.clear()
        auditLogPort.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()
        sessionRepo.clear()
        mfaRepo.clear()

        tenantRepo.add(acme)
        tenantRepo.add(globex)

        scimKey =
            (
                apiKeyService.create(
                    tenantId = acme.id,
                    name = "Provisioning Key",
                    scopes = listOf(ApiScope.SCIM),
                ) as ApiKeyResult.Success
            ).value.rawKey

        noScimKey =
            (
                apiKeyService.create(
                    tenantId = acme.id,
                    name = "Other Key",
                    scopes = listOf(ApiScope.USERS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun addUser(
        username: String,
        tenantId: TenantId = acme.id,
    ): User =
        userRepo.add(
            User(
                tenantId = tenantId,
                username = username,
                email = "$username@example.com",
                fullName = username,
                passwordHash = User.SENTINEL_PASSWORD_HASH,
                enabled = true,
            ),
        )

    private fun addGroup(
        name: String,
        tenantId: TenantId = acme.id,
        externalId: String? = null,
        roleIds: List<RoleId> = emptyList(),
        members: List<User> = emptyList(),
        parentGroupId: GroupId? = null,
    ): Group {
        val group =
            groupRepo.add(
                Group(
                    tenantId = tenantId,
                    name = name,
                    externalId = externalId,
                    roleIds = roleIds,
                    parentGroupId = parentGroupId,
                ),
            )
        members.forEach { groupRepo.addUserToGroup(it.id!!, group.id!!) }
        return group
    }

    private fun groupsUrl(
        filter: String? = null,
        tenantSlug: String = "acme",
    ): String {
        val base = "/t/$tenantSlug/scim/v2/Groups"
        return if (filter == null) base else base + "?filter=" + java.net.URLEncoder.encode(filter, "UTF-8")
    }

    private fun groupUrl(
        id: Int,
        tenantSlug: String = "acme",
    ): String = "/t/$tenantSlug/scim/v2/Groups/$id"

    private fun createBody(
        displayName: String,
        externalId: String? = null,
        memberIds: List<Int> = emptyList(),
    ): String {
        val members = memberIds.joinToString(",") { """{"value":"$it","type":"User"}""" }
        val externalIdPart = externalId?.let { ""","externalId":"$it"""" } ?: ""
        return """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"$displayName"""" +
            """$externalIdPart,"members":[$members]}"""
    }

    private fun patchBody(vararg ops: String) =
        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[${ops.joinToString(",")}]}"""

    private fun membersArrayValue(vararg ids: Int) = "[" + ids.joinToString(",") { """{"value":"$it"}""" } + "]"

    // -------------------------------------------------------------------------
    // POST / GET / filtering
    // -------------------------------------------------------------------------

    @Test
    fun `POST creates a group and returns 201 with a Location header`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", externalId = "grp-1"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                true,
                response.headers["Location"]?.endsWith(
                    "/Groups/${groupRepo.findByExternalId(acme.id, "grp-1")!!.id!!.value}",
                ),
            )
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Engineering", body["displayName"]!!.jsonPrimitive.content)
            assertEquals("grp-1", body["externalId"]!!.jsonPrimitive.content)
            assertEquals(0, body["members"]!!.jsonArray.size)
        }

    @Test
    fun `meta location is present and agrees with the POST Location header`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val locationHeader = response.headers["Location"]
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val metaLocation =
                body["meta"]!!
                    .jsonObject["location"]!!
                    .jsonPrimitive.content
            assertEquals(locationHeader, metaLocation)
        }

    @Test
    fun `POST with a member includes it in the created resource`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(alice.id!!.value)))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                listOf(alice.id!!.value.toString()),
                body["members"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content },
            )
        }

    @Test
    fun `POST with a member from another workspace is rejected`() =
        testApplication {
            application { installTestApp() }
            val foreign = addUser("mallory", tenantId = globex.id)

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(foreign.id!!.value)))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects a duplicate displayName with a uniqueness conflict`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `POST rejects a nested group member with 400 invalidValue`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng",""" +
                            """"members":[{"value":"1","type":"Group"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects a nested group member regardless of casing`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng",""" +
                            """"members":[{"value":"1","type":"group"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a SCIM key without the scim scope is rejected`() =
        testApplication {
            application { installTestApp() }

            val response = client.get(groupsUrl()) { bearerAuth(noScimKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET Groups filters by externalId via the indexed fast path`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering", externalId = "grp-1")
            addGroup("Sales", externalId = "grp-2")

            val response = client.get(groupsUrl("""externalId eq "grp-2"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(
                "Sales",
                body["Resources"]!!
                    .jsonArray[0]
                    .jsonObject["displayName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `GET Groups filters by displayName via the bounded scan`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")
            addGroup("Sales")

            val response = client.get(groupsUrl("""displayName eq "Engineering"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(
                "Engineering",
                body["Resources"]!!
                    .jsonArray[0]
                    .jsonObject["displayName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `GET Groups with no filter returns every group in the tenant, scoped to it`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")
            addGroup("Sales")
            addGroup("Other Workspace Group", tenantId = globex.id)

            val response = client.get(groupsUrl()) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET Groups loads membership for the page in one batch, not once per group`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val bob = addUser("bob")
            addGroup("Engineering", members = listOf(alice))
            addGroup("Sales", members = listOf(bob))
            groupRepo.findUserIdsForGroupsCallSizes.clear()

            val response = client.get(groupsUrl()) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(2), groupRepo.findUserIdsForGroupsCallSizes)
        }

    // -------------------------------------------------------------------------
    // GET /Groups/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `GET Groups id includes members`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val bob = addUser("bob")
            val group = addGroup("Engineering", members = listOf(alice, bob))

            val response = client.get(groupUrl(group.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val values = body["members"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf(alice.id!!.value.toString(), bob.id!!.value.toString()), values)
        }

    @Test
    fun `a group in another workspace is 404, never 403`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response = client.get(groupUrl(foreignGroup.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT on another workspace's group is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response =
                client.put(groupUrl(foreignGroup.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Renamed"))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PATCH on another workspace's group is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response =
                client.patch(groupUrl(foreignGroup.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"remove","path":"members"}"""))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `DELETE on another workspace's group is 404 and leaves it intact`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response = client.delete(groupUrl(foreignGroup.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(foreignGroup, groupRepo.findById(foreignGroup.id))
        }

    // -------------------------------------------------------------------------
    // PUT — full replace
    // -------------------------------------------------------------------------

    @Test
    fun `PUT role assignments survive a PUT that omits them`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val group = addGroup("Engineering", roleIds = listOf(RoleId(42)), members = listOf(alice))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(alice.id!!.value)))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(RoleId(42)), groupRepo.findById(group.id)!!.roleIds)
        }

    @Test
    fun `PUT omitting members clears the group, matching full-replace semantics`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val group = addGroup("Engineering", members = listOf(alice))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Engineering"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PUT omitting externalId clears it`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering", externalId = "grp-1")

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Engineering"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(groupRepo.findById(group.id)!!.externalId)
        }

    // -------------------------------------------------------------------------
    // PATCH — the member matrix. Every assertion is on the COMPLETE resulting
    // membership, never just on the member that changed.
    // -------------------------------------------------------------------------

    @Test
    fun `PATCH add members appends to the existing collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":${membersArrayValue(u3.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                setOf(u1.id!!, u2.id!!, u3.id!!),
                groupRepo.findUserIdsInGroup(group.id).toSet(),
            )
        }

    @Test
    fun `PATCH add with a bare unwrapped member object appends, not replaces`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":{"value":"${u3.id!!.value}"}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                setOf(u1.id!!, u2.id!!, u3.id!!),
                groupRepo.findUserIdsInGroup(group.id).toSet(),
            )
        }

    @Test
    fun `PATCH a pathless add of members appends and every existing member survives`() =
        testApplication {
            application { installTestApp() }
            // Deliberately many members: replacing instead of appending would leave the group
            // with just the one member the connector sent, losing every role it granted.
            val existingMembers = (1..400).map { addUser("u$it") }
            val group = addGroup("Engineering", members = existingMembers)
            val newMember = addUser("newHire")

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","value":{"members":${membersArrayValue(newMember.id!!.value)}}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val expected = (existingMembers.map { it.id!! } + newMember.id!!).toSet()
            assertEquals(expected, groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH replace with plain-string member values is rejected and membership is unchanged`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody("""{"op":"replace","path":"members","value":["7","8"]}"""),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
            assertEquals(setOf(u1.id!!, u2.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members filter removes exactly the matched member`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members[value eq \"${u2.id!!.value}\"]"}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                setOf(u1.id!!, u3.id!!),
                groupRepo.findUserIdsInGroup(group.id).toSet(),
            )
        }

    @Test
    fun `PATCH remove members with no filter empties the group`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"remove","path":"members"}"""))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH replace members replaces the whole collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u9 = addUser("u9")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"replace","path":"members","value":${membersArrayValue(u9.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf(u9.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH a remove that empties the group followed by an add in the same request lands as a proper collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val newMember = addUser("newMember")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members"}""",
                            """{"op":"add","path":"members","value":${membersArrayValue(newMember.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                listOf(newMember.id!!.value.toString()),
                body["members"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content },
            )
            assertEquals(setOf(newMember.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH rejects a nested group member with 400 invalidValue`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering")

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":[{"value":"1","type":"GROUP"}]}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH a member from another workspace is rejected and does not partially apply`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val foreign = addUser("mallory", tenantId = globex.id)
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":${membersArrayValue(foreign.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(setOf(u1.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH can rename displayName without touching membership`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"replace","path":"displayName","value":"Engineering Team"}"""))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Engineering Team", groupRepo.findById(group.id)!!.name)
            assertEquals(setOf(u1.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE removes the group but leaves member users intact`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val group = addGroup("Engineering", members = listOf(alice))

            val deleteResponse = client.delete(groupUrl(group.id!!.value)) { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getGroupResponse = client.get(groupUrl(group.id.value)) { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.NotFound, getGroupResponse.status)

            val getUserResponse = client.get("/t/acme/scim/v2/Users/${alice.id!!.value}") { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.OK, getUserResponse.status)
        }

    @Test
    fun `DELETE on a group with children is refused and the children still exist`() =
        testApplication {
            application { installTestApp() }
            val parent = addGroup("Engineering")
            val child = addGroup("Backend", parentGroupId = parent.id)

            val response = client.delete(groupUrl(parent.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(true, groupRepo.findById(parent.id) != null)
            assertEquals(true, groupRepo.findById(child.id!!) != null)
        }

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            bearer("api-key") {
                realm = "KotAuth REST API"
                authenticate { creds ->
                    if (creds.token.startsWith("kauth_")) ApiKeyPrincipal(rawToken = creds.token) else null
                }
            }
        }
        routing {
            apiRoutes(
                apiKeyService = apiKeyService,
                tenantRepository = tenantRepo,
                roleRepository = roleRepo,
                groupRepository = groupRepo,
                applicationRepository = appRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                roleGroupService = roleGroupService,
                accountService = accountService,
                adminUserService = adminUserService,
                mfaService = mfaService,
                applicationManagementService = applicationManagementService,
                userAttributeService = userAttributeService,
                claimMapperService = claimMapperService,
                emailOtpService = stubEmailOtpService(),
                otpEmailRateLimiter = AlwaysAllowLimiter(),
                otpIpRateLimiter = AlwaysAllowLimiter(),
                apiWriteRateLimiter = AlwaysAllowLimiter(),
                apiReadRateLimiter = AlwaysAllowLimiter(),
                webhookService = webhookService,
                resourceServerService = ResourceServerService(FakeResourceServerRepository()),
                webAuthnService =
                    WebAuthnService(
                        credentialRepository = FakeWebAuthnCredentialRepository(),
                        relyingParty = FakeRelyingPartyAdapter(),
                        secretKey = "test-secret-key-32chars-long-xxxx",
                        auditLog = FakeAuditLogPort(),
                        userRepository = FakeUserRepository(),
                    ),
                webAuthnCredentialRepository = FakeWebAuthnCredentialRepository(),
                transactionRunner = transactionRunner,
                userRepository = userRepo,
            )
        }
    }
}
