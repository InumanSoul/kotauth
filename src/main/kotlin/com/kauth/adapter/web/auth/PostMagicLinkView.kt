package com.kauth.adapter.web.auth

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
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
            title { +EnglishStrings.POST_MAGIC_LINK_TITLE }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            style { unsafe { +ctx.theme.toCssVars() } }
            link(rel = "stylesheet", href = "/static/kotauth-auth.css?v=${AppInfo.assetVersion}")
        }
        body {
            demoBanner()
            div("shell") {
                div("card") {
                    h1("card-title") { +EnglishStrings.POST_MAGIC_LINK_TITLE }
                    p("card-subtitle") { +EnglishStrings.POST_MAGIC_LINK_INTRO }

                    button(classes = "btn") {
                        id = "enroll-passkey-btn"
                        type = ButtonType.button
                        +EnglishStrings.POST_MAGIC_LINK_ENROLL_CTA
                    }

                    div("footer-link") {
                        a(href = "/t/$tenantSlug/launcher", classes = "link") {
                            +EnglishStrings.POST_MAGIC_LINK_SKIP_CTA
                        }
                    }
                }
            }

            script(src = "/static/js/kotauth-passkeys.min.js?v=${AppInfo.assetVersion}") {
                attributes["defer"] = "true"
                JsIntegrity.passkeys?.let { attributes["integrity"] = it }
                attributes["crossorigin"] = "anonymous"
            }
            script {
                unsafe {
                    +"""
document.addEventListener('DOMContentLoaded', function () {
  document.getElementById('enroll-passkey-btn').addEventListener('click', async function () {
    try {
      await Kotauth.passkeys.enrollPasskey('/t/$tenantSlug/passkeys', 'This device');
      window.location.assign('/t/$tenantSlug/launcher');
    } catch (e) {
      alert('Failed to add passkey: ' + e.message);
    }
  });
});
""".trimIndent()
                }
            }
        }
    }
