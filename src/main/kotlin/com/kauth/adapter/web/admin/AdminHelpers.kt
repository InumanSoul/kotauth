package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
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
