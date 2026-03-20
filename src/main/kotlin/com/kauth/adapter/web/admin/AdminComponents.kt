package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Renders a [FlowContent.() -> Unit] lambda to an HTML string fragment.
 *
 * Used by htmx fragment endpoints that need to return partial HTML
 * (not a full `<!DOCTYPE html>` page). The lambda should render exactly
 * one root element (e.g. a `div("section") { ... }`) — that element
 * becomes the top-level of the returned string.
 *
 * Implementation note: we use a transparent `<div>` as the kotlinx.html
 * entry point, then strip it to return only the inner content.
 */
internal fun renderFragment(block: DIV.() -> Unit): String {
    val wrapped = createHTML(prettyPrint = false).div { block() }
    // Strip the outer <div>...</div> wrapper added by createHTML().div
    return wrapped.removePrefix("<div>").removeSuffix("</div>")
}

/** Copy glyph — used as button text content, not SVG. */
private const val COPY_GLYPH = "⎘"

/**
 * Renders a breadcrumb trail using BEM classes.
 *
 * @param crumbs Pairs of (label, href). The last crumb should have href = null
 *               to render as `.breadcrumb__current`.
 *
 * Usage:
 * ```
 * breadcrumb(
 *     "Workspaces" to "/admin",
 *     workspace.slug to "/admin/workspaces/${workspace.slug}",
 *     "Users" to null
 * )
 * ```
 */
fun DIV.breadcrumb(vararg crumbs: Pair<String, String?>) {
    nav("breadcrumb") {
        crumbs.forEachIndexed { i, (label, href) ->
            if (i > 0) span("breadcrumb__sep") { +"›" }
            if (href != null) {
                a(href, classes = "breadcrumb__link") { +label }
            } else {
                span("breadcrumb__current") { +label }
            }
        }
    }
}

/**
 * Renders the standard page header with title, optional meta row, and actions.
 */
fun DIV.pageHeader(
    title: String,
    left: (DIV.() -> Unit)? = null,
    meta: (DIV.() -> Unit)? = null,
    actions: (DIV.() -> Unit)? = null,
) {
    div("page-header") {
        div("page-header__left") {
            left?.invoke(this)
            div("page-header__identity") {
                h1("page-header__title") { +title }
                if (meta != null) {
                    div("page-header__meta") { meta() }
                }
            }
        }
        if (actions != null) {
            div("page-header__actions") { actions() }
        }
    }
}

/**
 * Simpler page header variant with title-row layout (title + inline badges).
 * Used on app-detail where badges sit on the same line as the title.
 */
fun DIV.pageHeaderWithTitleRow(
    title: String,
    titleBadge: (SPAN.() -> Unit)? = null,
    meta: (DIV.() -> Unit)? = null,
    actions: (DIV.() -> Unit)? = null,
) {
    div("page-header") {
        div("page-header__left") {
            div("page-header__title-row") {
                h1("page-header__title") { +title }
                if (titleBadge != null) {
                    span { titleBadge() }
                }
            }
            if (meta != null) {
                div("page-header__meta") { meta() }
            }
        }
        if (actions != null) {
            div("page-header__actions") { actions() }
        }
    }
}

/**
 * Renders a content section with header (title + optional action).
 */
fun DIV.section(
    title: String,
    description: String? = null,
    danger: Boolean = false,
    action: (DIV.() -> Unit)? = null,
    content: DIV.() -> Unit,
) {
    div("section") {
        div("section__header") {
            div {
                span("section__title${if (danger) " section__title--danger" else ""}") { +title }
                if (description != null) {
                    div("section__desc") { +description }
                }
            }
            if (action != null) {
                action()
            }
        }
        content()
    }
}

/** Starts an ov-card block. Content lambda receives the DIV to add rows. */
fun DIV.ovCard(content: DIV.() -> Unit) {
    div("ov-card") { content() }
}

/** Standard label-value row inside an ov-card. */
fun DIV.ovRow(
    label: String,
    value: DIV.() -> Unit,
) {
    div("ov-card__row") {
        span("ov-card__label") { +label }
        div("ov-card__value") { value() }
    }
}

/** Monospace accent value row with optional copy button. */
fun DIV.ovRowMono(
    label: String,
    value: String,
    copyable: Boolean = false,
) {
    div("ov-card__row") {
        span("ov-card__label") { +label }
        span("ov-card__value") {
            span("ov-card__value--mono") { +value }
            if (copyable) {
                copyBtn(value)
            }
        }
    }
}

/** Plain text value row. */
fun DIV.ovRowText(
    label: String,
    value: String,
) {
    ovRow(label) { +value }
}

/** Muted / secondary value row. */
fun DIV.ovRowMuted(
    label: String,
    value: String,
) {
    div("ov-card__row") {
        span("ov-card__label") { +label }
        span("ov-card__value ov-card__value--muted") { +value }
    }
}

/** Section label divider row inside an ov-card. */
fun DIV.ovSectionLabel(label: String) {
    div("ov-card__section-label") { +label }
}

/** Inherited-from-workspace row. */
fun DIV.ovRowInherited(
    label: String,
    workspaceHref: String,
) {
    div("ov-card__row ov-card__row--inherited") {
        span("ov-card__label") { +label }
        span("ov-card__value ov-card__value--inherited") {
            +"Inherited from workspace"
            a(workspaceHref, classes = "inherited-link") {
                +"View settings"
                inlineSvgIcon("arrow-small", "arrow")
            }
        }
    }
}

// ─── Small Components ───────────────────────────────────────────────────────

/** Copy-to-clipboard button. */
fun FlowOrInteractiveOrPhrasingContent.copyBtn(textToCopy: String) {
    button(classes = "copy-btn") {
        attributes["onclick"] =
            "navigator.clipboard.writeText('$textToCopy')" +
            ".then(()=>{this.textContent='✓';" +
            "setTimeout(()=>this.textContent='⎘',1200)})"
        attributes["title"] = "Copy"
        +COPY_GLYPH
    }
}

/** Notice banner (amber warning by default). */
fun DIV.notice(
    title: String,
    description: String,
    linkHref: String? = null,
    linkText: String? = null,
) {
    div("notice") {
        span("notice__icon") { inlineSvgIcon("warning", "warning") }
        div("notice__body") {
            span("notice__title") { +title }
            span("notice__desc") { +description }
        }
        if (linkHref != null && linkText != null) {
            a(linkHref, classes = "notice__link") {
                +linkText
                inlineSvgIcon("arrow-small", "arrow")
            }
        }
    }
}

/** Horizontal divider. */
fun DIV.divider() {
    div("divider") {}
}

/** BEM empty state with icon, title, description, and optional CTA. */
fun DIV.emptyState(
    iconName: String,
    title: String,
    description: String,
    cta: (DIV.() -> Unit)? = null,
) {
    div("empty-state") {
        div("empty-state__icon") { inlineSvgIcon(iconName, title) }
        p("empty-state__title") { +title }
        p("empty-state__desc") { +description }
        cta?.invoke(this)
    }
}

/** Danger zone card with description and an action slot (typically a button). */
fun DIV.dangerZoneCard(
    title: String,
    description: String,
    warning: Boolean = false,
    action: DIV.() -> Unit,
) {
    div("danger-zone__card${if (warning) " danger-zone__card--warning" else ""}") {
        div {
            p("danger-zone__title${if (warning) " danger-zone__title--warning" else ""}") { +title }
            p("danger-zone__desc") { +description }
        }
        action()
    }
}

// ─── Button Helpers ─────────────────────────────────────────────────────────

/** Ghost button link with external-link arrow icon. */
fun DIV.ghostLinkExternal(
    href: String,
    label: String,
) {
    a(href, classes = "btn btn--ghost") {
        attributes["target"] = "_blank"
        +label
        inlineSvgIcon("external-link", "external link")
    }
}

/** Primary button link with optional icon. */
fun DIV.primaryLink(
    href: String,
    label: String,
    iconName: String? = null,
) {
    a(href, classes = "btn btn--primary") {
        if (iconName != null) inlineSvgIcon(iconName, label)
        +label
    }
}

/** POST form with a single button — for state-changing actions like toggle, revoke. */
fun DIV.postButton(
    action: String,
    label: String,
    btnClass: String = "btn btn--ghost btn--sm",
) {
    form(action = action, method = FormMethod.post, classes = "inline-form") {
        button(type = ButtonType.submit, classes = btnClass) { +label }
    }
}

// ─── Timestamp Formatting ───────────────────────────────────────────────────

private val TS_FMT =
    java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")

fun java.time.Instant.toDisplayString(): String = TS_FMT.format(this.atOffset(java.time.ZoneOffset.UTC))
