package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey

/** Rows per page in the admin user list. Shared by the route that slices and the view that labels. */
internal const val DEFAULT_USER_PAGE_SIZE = 25

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

/**
 * Derives the base URL from the current request's local connection info.
 * Used to construct callback URLs, invite links, and verification links.
 *
 * Example: `http://localhost:8080` or `https://auth.example.com:443`
 */
fun ApplicationCall.resolvedBaseUrl(): String =
    request.origin.let {
        val omitPort = (it.scheme == "https" && it.serverPort == 443) || (it.scheme == "http" && it.serverPort == 80)
        if (omitPort) "${it.scheme}://${it.serverHost}" else "${it.scheme}://${it.serverHost}:${it.serverPort}"
    }

/**
 * Escapes [literal] for use in an HTML5 `pattern` attribute.
 *
 * `pattern` is compiled as an ECMAScript RegExp. `java.util.regex.Pattern.quote` emits
 * the Java-only `\Q…\E` form, which ECMAScript cannot compile — and a `pattern` that
 * fails to compile is dropped entirely, silently removing the constraint. Escaping each
 * metacharacter individually keeps the attribute valid in both engines.
 */
internal fun escapeForHtmlPattern(literal: String): String =
    literal
        .map { character ->
            if (character in ECMASCRIPT_REGEX_METACHARACTERS) "\\$character" else "$character"
        }.joinToString("")

private const val ECMASCRIPT_REGEX_METACHARACTERS = "^$\\.*+?()[]{}|/-"
