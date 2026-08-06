package com.kauth.adapter.web.auth

import com.kauth.domain.model.LoginLayout
import com.kauth.domain.model.TenantTheme
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.aside
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.img
import kotlinx.html.main
import kotlinx.html.p

/**
 * Renders the shared shell that wraps every auth page.
 *
 * `CENTERED` (default): single-column card centered on the page. The brand
 * block sits at the top, above the card content.
 *
 * `SPLIT`: two-column with a branded left panel (background image or solid
 * accent color, with tagline) and the card on the right. Collapses to a
 * single column on viewports narrower than ~640px (see shell.css).
 *
 * The shell renders <div class="shell"> [+ modifier + data-layout attr] and
 * hosts a brand block (logo or workspace name) before the passed content.
 * Pass the card content as the trailing lambda.
 *
 * @param brandExtra  Optional additional markup rendered inside the brand
 *                     block, after the logo/workspace-name — e.g. the MFA
 *                     challenge page's tagline line. Defaults to nothing.
 */
internal fun BODY.authShell(
    workspaceName: String,
    theme: TenantTheme,
    brandExtra: FlowContent.() -> Unit = {},
    content: FlowContent.() -> Unit,
) {
    val shellClasses = if (theme.loginLayout == LoginLayout.SPLIT) "shell shell--split" else "shell"
    div(shellClasses) {
        attributes["data-layout"] = theme.loginLayout.name.lowercase()
        if (theme.loginLayout == LoginLayout.SPLIT) {
            val bgStyle = theme.loginBackgroundUrl?.let { "background-image:url('${sanitizeCssUrl(it)}')" } ?: ""
            aside(classes = "shell__panel") {
                if (bgStyle.isNotEmpty()) attributes["style"] = bgStyle
                div("shell__panel-inner") {
                    p("shell__tagline") { +(theme.loginTagline ?: workspaceName) }
                }
            }
            main(classes = "shell__form") {
                brandBlock(theme, workspaceName, brandExtra)
                content()
            }
        } else {
            brandBlock(theme, workspaceName, brandExtra)
            content()
        }
    }
}

// kotlinx.html attribute escaping doesn't encode single quotes, but the CSS url('...')
// literal here is single-quoted — encode characters that could break out of it or the style attribute.
private fun sanitizeCssUrl(url: String): String =
    url
        .replace("\\", "%5C")
        .replace("'", "%27")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

internal fun FlowContent.brandBlock(
    theme: TenantTheme,
    workspaceName: String,
    extra: FlowContent.() -> Unit = {},
) {
    div("brand") {
        if (theme.logoUrl != null) {
            img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName) {
                width = "180"
                height = "48"
            }
        } else {
            div("brand-name") { +workspaceName }
        }
        extra()
    }
}
