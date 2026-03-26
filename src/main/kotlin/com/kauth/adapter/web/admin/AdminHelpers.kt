package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import io.ktor.util.AttributeKey

/** Resolved workspace for the current `/{slug}` admin route. */
internal val WorkspaceAttr = AttributeKey<Tenant>("Workspace")

/** Slug → display-name pairs for the workspace sidebar navigation. */
internal val WsPairsAttr = AttributeKey<List<Pair<String, String>>>("WsPairs")
