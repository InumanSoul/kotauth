package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.Group
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.Role
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
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

    fun loginPage(
        error: String? = null,
        bypassNotice: String? = null,
    ): HTML.() -> Unit =
        loginPageImpl(error, bypassNotice)

    fun errorPage(
        message: String,
        retryUrl: String,
    ): HTML.() -> Unit =
        adminOAuthErrorPageImpl(message, retryUrl)

    fun adminErrorPage(
        message: String,
        exceptionType: String? = null,
        allWorkspaces: List<Pair<String, String>> = emptyList(),
        loggedInAs: String = "—",
    ): HTML.() -> Unit = adminErrorPageImpl(message, exceptionType, allWorkspaces, loggedInAs)

    // ── Dashboard / redirect ────────────────────────────────────────────

    fun workspaceRedirector(fallbackSlug: String): HTML.() -> Unit =
        workspaceRedirectorImpl(fallbackSlug)

    fun workspaceListPage(
        workspaces: List<Tenant>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit = workspaceListPageImpl(workspaces, allWorkspaces, loggedInAs)

    // ── Workspace ───────────────────────────────────────────────────────

    fun createWorkspacePage(
        loggedInAs: String,
        allWorkspaces: List<Pair<String, String>> = emptyList(),
        error: String? = null,
        prefill: WorkspacePrefill = WorkspacePrefill(),
    ): HTML.() -> Unit = createWorkspacePageImpl(loggedInAs, allWorkspaces, error, prefill)

    fun workspaceDetailPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        apps: List<Application> = emptyList(),
        loggedInAs: String,
    ): HTML.() -> Unit = workspaceDetailPageImpl(workspace, allWorkspaces, apps, loggedInAs)

    fun workspaceSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = workspaceSettingsPageImpl(workspace, allWorkspaces, loggedInAs, error, saved)

    fun securityPolicyPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = securityPolicyPageImpl(workspace, allWorkspaces, loggedInAs, error, saved)

    fun brandingPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = brandingPageImpl(workspace, allWorkspaces, loggedInAs, error, saved)

    // ── Application ─────────────────────────────────────────────────────

    fun createApplicationPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        prefill: ApplicationPrefill = ApplicationPrefill(),
    ): HTML.() -> Unit = createApplicationPageImpl(workspace, allWorkspaces, loggedInAs, error, prefill)

    fun applicationDetailPage(
        workspace: Tenant,
        application: Application,
        allWorkspaces: List<Pair<String, String>>,
        allApps: List<Application>,
        loggedInAs: String,
        newSecret: String? = null,
    ): HTML.() -> Unit = applicationDetailPageImpl(workspace, application, allWorkspaces, allApps, loggedInAs, newSecret)

    fun editApplicationPage(
        workspace: Tenant,
        application: Application,
        allWorkspaces: List<Pair<String, String>>,
        allApps: List<Application>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = editApplicationPageImpl(workspace, application, allWorkspaces, allApps, loggedInAs, error)

    // ── User ────────────────────────────────────────────────────────────

    fun userListPage(
        workspace: Tenant,
        users: List<User>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        search: String? = null,
    ): HTML.() -> Unit = userListPageImpl(workspace, users, allWorkspaces, loggedInAs, search)

    fun createUserPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        prefill: UserPrefill = UserPrefill(),
    ): HTML.() -> Unit = createUserPageImpl(workspace, allWorkspaces, loggedInAs, error, prefill)

    fun userDetailPage(
        workspace: Tenant,
        user: User,
        sessions: List<Session>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        successMessage: String? = null,
        editError: String? = null,
        roles: List<Role> = emptyList(),
        groups: List<Group> = emptyList(),
    ): HTML.() -> Unit = userDetailPageImpl(workspace, user, sessions, allWorkspaces, loggedInAs, successMessage, editError, roles, groups)

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
    ): String = renderFragment { userProfileEditFragment(workspace, user, editError) }

    // ── Sessions & Audit ────────────────────────────────────────────────

    fun activeSessionsPage(
        workspace: Tenant,
        sessions: List<Session>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        userMap: Map<UserId, String> = emptyMap(),
    ): HTML.() -> Unit = activeSessionsPageImpl(workspace, sessions, allWorkspaces, loggedInAs, userMap)

    fun auditLogPage(
        workspace: Tenant,
        events: List<AuditEvent>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        page: Int = 1,
        totalPages: Int = 1,
        eventTypeFilter: String? = null,
        userMap: Map<UserId, String> = emptyMap(),
    ): HTML.() -> Unit = auditLogPageImpl(workspace, events, allWorkspaces, loggedInAs, page, totalPages, eventTypeFilter, userMap)

    // ── Settings ────────────────────────────────────────────────────────

    fun smtpSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = smtpSettingsPageImpl(workspace, allWorkspaces, loggedInAs, error, saved)

    // ── RBAC ────────────────────────────────────────────────────────────

    fun rolesListPage(
        workspace: Tenant,
        roles: List<Role>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit = rolesListPageImpl(workspace, roles, allWorkspaces, loggedInAs)

    fun createRolePage(
        workspace: Tenant,
        apps: List<Application>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = createRolePageImpl(workspace, apps, allWorkspaces, loggedInAs, error)

    fun roleDetailPage(
        workspace: Tenant,
        role: Role,
        allRoles: List<Role>,
        allUsers: List<User>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit = roleDetailPageImpl(workspace, role, allRoles, allUsers, allWorkspaces, loggedInAs)

    fun groupsListPage(
        workspace: Tenant,
        groups: List<Group>,
        roles: List<Role>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit = groupsListPageImpl(workspace, groups, roles, allWorkspaces, loggedInAs)

    fun createGroupPage(
        workspace: Tenant,
        groups: List<Group>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = createGroupPageImpl(workspace, groups, allWorkspaces, loggedInAs, error)

    fun groupDetailPage(
        workspace: Tenant,
        group: Group,
        allGroups: List<Group>,
        allRoles: List<Role>,
        members: List<User>,
        allUsers: List<User>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit = groupDetailPageImpl(workspace, group, allGroups, allRoles, members, allUsers, allWorkspaces, loggedInAs)

    // ── Security ────────────────────────────────────────────────────────

    fun mfaSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
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
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit = identityProvidersPageImpl(workspace, providers, allWorkspaces, loggedInAs, error, saved)

    fun apiKeysListPage(
        workspace: Tenant,
        apiKeys: List<ApiKey>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        newKeyRaw: String? = null,
        error: String? = null,
    ): HTML.() -> Unit = apiKeysListPageImpl(workspace, apiKeys, allWorkspaces, loggedInAs, newKeyRaw, error)

    fun createApiKeyPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        scopes: List<String> = ApiScope.ALL,
    ): HTML.() -> Unit = createApiKeyPageImpl(workspace, allWorkspaces, loggedInAs, error, scopes)

    // ── Webhooks ────────────────────────────────────────────────────────

    fun webhooksListPage(
        workspace: Tenant,
        endpoints: List<WebhookEndpoint>,
        deliveries: List<WebhookDelivery>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        newSecret: String? = null,
        error: String? = null,
    ): HTML.() -> Unit = webhooksListPageImpl(workspace, endpoints, deliveries, allWorkspaces, loggedInAs, newSecret, error)

    fun createWebhookPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit = createWebhookPageImpl(workspace, allWorkspaces, loggedInAs, error)
}
