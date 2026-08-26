package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.BrokeredSignInFailure
import com.kauth.domain.model.ProviderKey
import kotlinx.html.*
import java.time.Instant

/** How many failures one provider's panel shows. Enough to see a pattern, short enough to read. */
private const val FAILURES_PER_PROVIDER = 10

/**
 * One recorded sign-in failure, ready to render.
 *
 * Every field here comes from a [BrokeredSignInFailure] detail, so the panel can show only what a
 * refusal was allowed to record — there is no path from this row back to the person.
 */
data class SignInFailureRow(
    val at: Instant,
    val reason: String,
    val emailDomain: String?,
    val idpErrorCode: String?,
    val reference: String?,
)

/**
 * Groups recorded failures by the provider they happened on.
 *
 * A row whose provider key is not one this workspace configured is simply absent from the map,
 * which is what keeps a deleted provider's history out of its successor's panel.
 */
fun List<AuditEvent>.groupSignInFailuresByProvider(): Map<ProviderKey, List<SignInFailureRow>> =
    mapNotNull { event ->
        val key = event.details[BrokeredSignInFailure.PROVIDER]?.let { ProviderKey.of(it) } ?: return@mapNotNull null
        val reason = event.details[BrokeredSignInFailure.REASON] ?: return@mapNotNull null
        key to
            SignInFailureRow(
                at = event.createdAt,
                reason = reason,
                emailDomain = event.details[BrokeredSignInFailure.EMAIL_DOMAIN],
                idpErrorCode = event.details[BrokeredSignInFailure.IDP_ERROR_CODE],
                reference = event.details[BrokeredSignInFailure.REFERENCE],
            )
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, rows) -> rows.sortedByDescending { it.at }.take(FAILURES_PER_PROVIDER) }

/**
 * The recent sign-in failures for one provider.
 *
 * This panel is the only place a wrong callback URL becomes visible: testing an issuer's discovery
 * document proves the endpoints resolve, and says nothing about whether the provider recognises the
 * URL it is asked to redirect to. That only surfaces when a real person signs in and is turned
 * away, which is a thing the operator otherwise experiences as silence.
 */
internal fun FlowContent.identityProviderFailuresPanel(rows: List<SignInFailureRow>) {
    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.IDP_FAILURES_TITLE }
        div {
            if (rows.isEmpty()) {
                div("edit-row__hint") { +EnglishStrings.IDP_FAILURES_EMPTY }
            } else {
                table("data-table") {
                    thead {
                        tr {
                            th { +EnglishStrings.IDP_FAILURES_COL_WHEN }
                            th { +EnglishStrings.IDP_FAILURES_COL_REASON }
                            th { +EnglishStrings.IDP_FAILURES_COL_DOMAIN }
                            th { +EnglishStrings.IDP_FAILURES_COL_REFERENCE }
                        }
                    }
                    tbody {
                        rows.forEach { row ->
                            tr {
                                td { +row.at.toDisplayString() }
                                td {
                                    +reasonLabel(row.reason)
                                    // The provider's own error code, and only when it arrived in
                                    // the shape of one — the callback query is anyone's to write.
                                    row.idpErrorCode?.let { code -> +" — $code" }
                                }
                                td { +(row.emailDomain ?: "—") }
                                td { +(row.reference ?: "—") }
                            }
                        }
                    }
                }
            }
            div("edit-row__hint") { +EnglishStrings.IDP_FAILURES_HINT }
        }
    }
}

private fun reasonLabel(reason: String): String =
    when (reason) {
        BrokeredSignInFailure.EMAIL_NOT_VERIFIED -> EnglishStrings.IDP_FAILURE_EMAIL_NOT_VERIFIED
        BrokeredSignInFailure.DOMAIN_NOT_ALLOWED -> EnglishStrings.IDP_FAILURE_DOMAIN_NOT_ALLOWED
        BrokeredSignInFailure.USERNAME_CONFLICT -> EnglishStrings.IDP_FAILURE_USERNAME_CONFLICT
        BrokeredSignInFailure.IDP_RETURNED_ERROR -> EnglishStrings.IDP_FAILURE_IDP_RETURNED_ERROR
        else -> EnglishStrings.IDP_FAILURE_UNRECOGNISED
    }
