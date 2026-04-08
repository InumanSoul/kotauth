package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey

/** Resolved workspace for the current `/{slug}` admin route. */
internal val WorkspaceAttr = AttributeKey<Tenant>("Workspace")

/** Workspace stubs for the sidebar navigation and switcher. */
internal val WsPairsAttr = AttributeKey<List<WorkspaceStub>>("WsPairs")

/** Lightweight workspace info for navigation and avatar rendering. */
data class WorkspaceStub(
    val slug: String,
    val name: String,
    val logoUrl: String? = null,
)

/**
 * Common context resolved at the start of admin route handlers that render pages.
 *
 * Use [ApplicationCall.adminContext] to construct. Handlers that only need
 * [Tenant] for a redirect or query should read [WorkspaceAttr] directly.
 */
data class AdminRouteContext(
    val session: AdminSession,
    val workspace: Tenant,
    val wsPairs: List<WorkspaceStub>,
) {
    val slug: String get() = workspace.slug
}

/**
 * Extracts the standard admin route context from the current call.
 * Use in GET handlers that render full pages (need session + workspace + wsPairs).
 */
fun ApplicationCall.adminContext(): AdminRouteContext =
    AdminRouteContext(
        session = sessions.get<AdminSession>()!!,
        workspace = attributes[WorkspaceAttr],
        wsPairs = attributes[WsPairsAttr],
    )

/**
 * Extracts a typed integer ID from a path parameter.
 * Returns null if the parameter is missing or not a valid integer.
 *
 * Usage: `val userId = call.parameters.typedId("userId", ::UserId) ?: return@get ...`
 */
fun <T> Parameters.typedId(
    name: String,
    wrap: (Int) -> T,
): T? = get(name)?.toIntOrNull()?.let(wrap)
