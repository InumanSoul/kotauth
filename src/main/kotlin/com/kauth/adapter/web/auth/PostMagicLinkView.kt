package com.kauth.adapter.web.auth

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.JsIntegrity
import com.kauth.adapter.web.ViewContext
import com.kauth.adapter.web.demoBanner
import kotlinx.html.*

fun postMagicLinkPage(
    tenantSlug: String,
    ctx: ViewContext,
): HTML.() -> Unit =
    {
        head {
            title { +ctx.t("POST_MAGIC_LINK_TITLE") }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            style { unsafe { +ctx.theme.toCssVars() } }
            link(rel = "stylesheet", href = "/static/kotauth-auth.css?v=${AppInfo.assetVersion}")
        }
        body {
            demoBanner()
            div("shell") {
                div("card") {
                    h1("card-title") { +ctx.t("POST_MAGIC_LINK_TITLE") }
                    p("card-subtitle") { +ctx.t("POST_MAGIC_LINK_INTRO") }

                    button(classes = "btn") {
                        id = "enroll-passkey-btn"
                        type = ButtonType.button
                        +ctx.t("POST_MAGIC_LINK_ENROLL_CTA")
                    }

                    div("footer-link") {
                        a(href = "/t/$tenantSlug/launcher", classes = "link") {
                            +ctx.t("POST_MAGIC_LINK_SKIP_CTA")
                        }
                    }
                }
            }

            script(src = "/static/js/kotauth-passkeys.min.js?v=${AppInfo.assetVersion}") {
                attributes["defer"] = "true"
                JsIntegrity.passkeys?.let { attributes["integrity"] = it }
                attributes["crossorigin"] = "anonymous"
                attributes["data-passkey-base"] = "/t/$tenantSlug/passkeys"
                attributes["data-passkey-mode"] = "enroll"
                attributes["data-passkey-redirect"] = "/t/$tenantSlug/launcher"
                attributes["data-passkey-default-name"] = ctx.t("POST_MAGIC_LINK_PASSKEY_DEFAULT_NAME")
            }
        }
    }
