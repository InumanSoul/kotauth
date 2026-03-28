package com.kauth.adapter.web

import java.util.Properties

/**
 * Loads SRI (Subresource Integrity) hashes for JS bundles from
 * js-integrity.properties, generated at build time by generate-sri.js.
 *
 * Used by shell views to add `integrity="sha256-..."` to `<script>` tags.
 * If the properties file is missing (dev mode without a full build), hashes are null
 * and the integrity attribute is simply omitted.
 */
object JsIntegrity {
    private val props =
        Properties().also { p ->
            JsIntegrity::class.java
                .getResourceAsStream("/js-integrity.properties")
                ?.use(p::load)
        }

    val admin: String? = props.getProperty("js.admin.integrity")
    val auth: String? = props.getProperty("js.auth.integrity")
    val portal: String? = props.getProperty("js.portal.integrity")
    val branding: String? = props.getProperty("js.branding.integrity")
}
