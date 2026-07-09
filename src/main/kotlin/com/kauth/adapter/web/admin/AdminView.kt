package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.Group
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.Role
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantKey
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookEndpoint
import kotlinx.html.*

/**
 * View layer for the admin console.
 *
 * Pure functions: data in → HTML out. No HTTP context, no service calls, no side effects.
 * Terminology (public-facing):
 *   Workspace   = internal Tenant  (what an org owns)
 *   Application = internal Client  (what authenticates against a workspace)
 *
 */

object AdminView {
    @Volatile
    internal var shellAppInfo: AppInfo = AppInfo()

    fun setShellAppInfo(appInfo: AppInfo) {
        shellAppInfo = appInfo
    }

    // ── Auth ────────────────────────────────────────────────────────────

    fun errorPage(
        message: String,
        retryUrl: String,
    ): HTML.() -> Unit =
        adminOAuthErrorPageImpl(message, retryUrl)

    fun adminErrorPage(
        allWorkspaces: List<WorkspaceStub> = emptyList(),
        loggedInAs: String = "—",
    ): HTML.() -> Unit = adminErrorPageImpl(allWorkspaces, loggedInAs)

    // ── Dashboard / redirect ────────────────────────────────────────────

    fun workspaceListPage(
        workspaces: List<Tenant>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
    ): HTML.() -> Unit = workspaceListPageImpl(workspaces, allWorkspaces, loggedInAs)

    // ── Workspace ───────────────────────────────────────────────────────

    fun createWorkspacePage(
        loggedInAs: String,
        allWorkspaces: List<WorkspaceStub> = emptyList(),
        error: String? = null,
        prefill: WorkspacePrefill = WorkspacePrefill(),
    ): HTML.() -> Unit = createWorkspacePageImpl(loggedInAs, allWorkspaces, error, prefill)

    fun workspaceDetailPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        apps: List<Application> = emptyList(),
        loggedInAs: String,
    ): HTML.() -> Unit = workspaceDetailPageImpl(workspace, allWorkspaces, apps, loggedInAs)

    fun workspaceSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = workspaceSettingsPageImpl(workspace, allWorkspaces, loggedInAs, error, saved)

    fun securityPolicyPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        savedParam: String? = null,
        enrolledPasskeyUsers: Int = 0,
        totalUsers: Int = 0,
        rows: List<com.kauth.domain.model.AuthMethodRow> = emptyList(),
    ): HTML.() -> Unit =
        securityPolicyPageImpl(workspace, allWorkspaces, loggedInAs, error, savedParam, enrolledPasskeyUsers, totalUsers, rows)

    fun brandingPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        availableLocales: Set<String> = setOf("en"),
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit =
        brandingPageImpl(workspace, allWorkspaces, loggedInAs, availableLocales, error, saved)

    // ── Application ─────────────────────────────────────────────────────

    fun createApplicationPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        prefill: ApplicationPrefill = ApplicationPrefill(),
    ): HTML.() -> Unit = createApplicationPageImpl(workspace, allWorkspaces, loggedInAs, error, prefill)

    fun applicationDetailPage(
        workspace: Tenant,
        application: Application,
        allWorkspaces: List<WorkspaceStub>,
        allApps: List<Application>,
        loggedInAs: String,
        newSecret: String? = null,
        defaultRoles: List<com.kauth.domain.model.Role> = emptyList(),
        availableDefaultRoles: List<com.kauth.domain.model.Role> = emptyList(),
    ): HTML.() -> Unit =
        applicationDetailPageImpl(
            workspace,
            application,
            allWorkspaces,
            allApps,
            loggedInAs,
            newSecret,
            defaultRoles,
            availableDefaultRoles,
        )

    fun editApplicationPage(
        workspace: Tenant,
        application: Application,
        allWorkspaces: List<WorkspaceStub>,
        allApps: List<Application>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = editApplicationPageImpl(workspace, application, allWorkspaces, allApps, loggedInAs, error)

    // ── User ────────────────────────────────────────────────────────────

    fun userListPage(
        workspace: Tenant,
        users: List<User>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        search: String? = null,
        page: Int = 1,
        totalPages: Int = 1,
        totalCount: Long = 0,
    ): HTML.() -> Unit =
        userListPageImpl(workspace, users, allWorkspaces, loggedInAs, search, page, totalPages, totalCount)

    fun createUserPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        prefill: UserPrefill = UserPrefill(),
    ): HTML.() -> Unit = createUserPageImpl(workspace, allWorkspaces, loggedInAs, error, prefill)

    fun userDetailPage(
        workspace: Tenant,
        user: User,
        sessions: List<Session>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        successMessage: String? = null,
        editError: String? = null,
        roles: List<Role> = emptyList(),
        groups: List<Group> = emptyList(),
        userAttributes: Map<String, String> = emptyMap(),
        mappedKeys: Map<String, String> = emptyMap(),
        attributeError: String? = null,
        tempPasswordLink: String? = null,
        recentImpersonations: List<ImpersonationRecord> = emptyList(),
        recentOtpActivity: List<OtpActivityRecord> = emptyList(),
        passkeys: List<com.kauth.domain.model.WebAuthnCredential> = emptyList(),
    ): HTML.() -> Unit =
        userDetailPageImpl(
            workspace,
            user,
            sessions,
            allWorkspaces,
            loggedInAs,
            successMessage,
            editError,
            roles,
            groups,
            userAttributes,
            mappedKeys,
            attributeError,
            tempPasswordLink,
            recentImpersonations,
            recentOtpActivity,
            passkeys,
        )

    fun userAttributeFormPage(
        workspace: Tenant,
        user: User,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        existingKey: String? = null,
        prefillKey: String = "",
        prefillValue: String = "",
        error: String? = null,
    ): HTML.() -> Unit =
        userAttributeFormPageImpl(
            workspace,
            user,
            allWorkspaces,
            loggedInAs,
            existingKey,
            prefillKey,
            prefillValue,
            error,
        )

    // ── User htmx fragments ──────────────────────────────────────────

    /** Returns the read-only profile section as an HTML fragment string. */
    fun userProfileReadFragment(
        user: User,
        successMessage: String? = null,
        roles: List<Role> = emptyList(),
        groups: List<Group> = emptyList(),
    ): String = renderFragment { userProfileReadFragment(user, successMessage, roles, groups) }

    /** Returns the edit profile form section as an HTML fragment string. */
    fun userProfileEditFragment(
        workspace: Tenant,
        user: User,
        editError: String? = null,
        roles: List<Role> = emptyList(),
        groups: List<Group> = emptyList(),
    ): String = renderFragment { userProfileEditFragment(workspace, user, editError, roles, groups) }

    // ── Sessions & Audit ────────────────────────────────────────────────

    fun activeSessionsPage(
        workspace: Tenant,
        sessions: List<Session>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        userMap: Map<UserId, String> = emptyMap(),
        clientMap: Map<ApplicationId, String> = emptyMap(),
        savedParam: String? = null,
        totalCount: Int = 0,
    ): HTML.() -> Unit =
        activeSessionsPageImpl(workspace, sessions, allWorkspaces, loggedInAs, userMap, clientMap, savedParam, totalCount)

    fun auditLogPage(
        workspace: Tenant,
        events: List<AuditEvent>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        page: Int = 1,
        totalPages: Int = 1,
        eventTypeFilter: String? = null,
        userMap: Map<UserId, String> = emptyMap(),
        clientMap: Map<ApplicationId, String> = emptyMap(),
        clientLinks: Map<ApplicationId, ClientDisplayInfo> = emptyMap(),
    ): HTML.() -> Unit = auditLogPageImpl(workspace, events, allWorkspaces, loggedInAs, page, totalPages, eventTypeFilter, userMap, clientMap, clientLinks)

    // ── Settings ────────────────────────────────────────────────────────

    // ── Backup / Restore (v1.9.0) ──────────────────────────────────────

    fun workspaceBackupPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = workspaceBackupPageImpl(workspace, allWorkspaces, loggedInAs, error)

    fun importTenantPage(
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        prefillSlug: String? = null,
    ): HTML.() -> Unit = importTenantPageImpl(allWorkspaces, loggedInAs, error, prefillSlug)

    // ── (alphabetical-by-feature handle continues below)

    fun smtpSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        savedParam: String? = null,
    ): HTML.() -> Unit = smtpSettingsPageImpl(workspace, allWorkspaces, loggedInAs, error, savedParam)

    // ── RBAC ────────────────────────────────────────────────────────────

    fun rolesListPage(
        workspace: Tenant,
        roles: List<Role>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
    ): HTML.() -> Unit = rolesListPageImpl(workspace, roles, allWorkspaces, loggedInAs)

    fun createRolePage(
        workspace: Tenant,
        apps: List<Application>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = createRolePageImpl(workspace, apps, allWorkspaces, loggedInAs, error)

    fun roleDetailPage(
        workspace: Tenant,
        role: Role,
        allRoles: List<Role>,
        assignedUsers: List<User> = emptyList(),
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        toastMessage: String? = null,
    ): HTML.() -> Unit =
        roleDetailPageImpl(workspace, role, allRoles, assignedUsers, allWorkspaces, loggedInAs, toastMessage)

    fun groupsListPage(
        workspace: Tenant,
        groups: List<Group>,
        roles: List<Role>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
    ): HTML.() -> Unit = groupsListPageImpl(workspace, groups, roles, allWorkspaces, loggedInAs)

    fun createGroupPage(
        workspace: Tenant,
        groups: List<Group>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = createGroupPageImpl(workspace, groups, allWorkspaces, loggedInAs, error)

    fun groupDetailPage(
        workspace: Tenant,
        group: Group,
        allGroups: List<Group>,
        allRoles: List<Role>,
        members: List<User>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        toastMessage: String? = null,
    ): HTML.() -> Unit =
        groupDetailPageImpl(workspace, group, allGroups, allRoles, members, allWorkspaces, loggedInAs, toastMessage)

    // ── Security ────────────────────────────────────────────────────────

    fun mfaSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        totalUsers: Int = 0,
        enrolledUsers: Int = 0,
        enrolledUserList: List<User> = emptyList(),
        notEnrolledUserList: List<User> = emptyList(),
    ): HTML.() -> Unit =
        mfaSettingsPageImpl(
            workspace,
            allWorkspaces,
            loggedInAs,
            totalUsers,
            enrolledUsers,
            enrolledUserList,
            notEnrolledUserList,
        )

    fun identityProvidersPage(
        workspace: Tenant,
        providers: List<IdentityProvider>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = identityProvidersPageImpl(workspace, providers, allWorkspaces, loggedInAs, error, saved)

    fun apiKeysListPage(
        workspace: Tenant,
        apiKeys: List<ApiKey>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        newKeyRaw: String? = null,
        error: String? = null,
    ): HTML.() -> Unit = apiKeysListPageImpl(workspace, apiKeys, allWorkspaces, loggedInAs, newKeyRaw, error)

    fun createApiKeyPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
        scopes: List<String> = ApiScope.ALL,
    ): HTML.() -> Unit = createApiKeyPageImpl(workspace, allWorkspaces, loggedInAs, error, scopes)

    // ── Webhooks ────────────────────────────────────────────────────────

    fun webhooksListPage(
        workspace: Tenant,
        endpoints: List<WebhookEndpoint>,
        deliveries: List<WebhookDelivery>,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        newSecret: String? = null,
        error: String? = null,
    ): HTML.() -> Unit = webhooksListPageImpl(workspace, endpoints, deliveries, allWorkspaces, loggedInAs, newSecret, error)

    fun createWebhookPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = createWebhookPageImpl(workspace, allWorkspaces, loggedInAs, error)

    // ── Signing Keys ────────────────────────────────────────────────────

    fun keyManagementPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        keys: List<TenantKey>,
        error: String? = null,
        toastMessage: String? = null,
    ): HTML.() -> Unit = keyManagementPageImpl(workspace, allWorkspaces, loggedInAs, keys, error, toastMessage)

    // ── Claim Mappers ───────────────────────────────────────────────────

    fun claimMappersListPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        mappers: List<com.kauth.domain.model.TenantClaimMapper>,
        error: String? = null,
        toastMessage: String? = null,
    ): HTML.() -> Unit =
        claimMappersListPageImpl(workspace, allWorkspaces, loggedInAs, mappers, error, toastMessage)

    fun claimMapperFormPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        prefill: com.kauth.domain.model.TenantClaimMapper? = null,
        error: String? = null,
    ): HTML.() -> Unit =
        claimMapperFormPageImpl(workspace, allWorkspaces, loggedInAs, prefill, error)

    fun resourceServersListPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        resources: List<com.kauth.domain.model.ResourceServer>,
        error: String? = null,
        toastMessage: String? = null,
    ): HTML.() -> Unit =
        resourceServersListPageImpl(workspace, allWorkspaces, loggedInAs, resources, error, toastMessage)

    fun resourceServerFormPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        prefill: com.kauth.domain.model.ResourceServer? = null,
        error: String? = null,
    ): HTML.() -> Unit =
        resourceServerFormPageImpl(workspace, allWorkspaces, loggedInAs, prefill, error)

    fun clientAuthorizedApisPage(
        workspace: Tenant,
        allWorkspaces: List<WorkspaceStub>,
        loggedInAs: String,
        application: com.kauth.domain.model.Application,
        allApps: List<com.kauth.domain.model.Application>,
        allResources: List<com.kauth.domain.model.ResourceServer>,
        authorizedIds: Set<Int>,
        error: String? = null,
        toastMessage: String? = null,
    ): HTML.() -> Unit =
        clientAuthorizedApisPageImpl(
            workspace,
            allWorkspaces,
            loggedInAs,
            application,
            allApps,
            allResources,
            authorizedIds,
            error,
            toastMessage,
        )
}
