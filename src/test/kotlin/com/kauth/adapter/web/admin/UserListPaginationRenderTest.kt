package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The user list's "Showing N–M of T" range and its pagination links.
 *
 * The range used to be derived from the row count of whichever page was rendered, which
 * is only equal to the page size until the last page — the page every operator reaches
 * by clicking to the end. The search term went into the next-page URL unencoded.
 */
class UserListPaginationRenderTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val workspace =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    private fun users(count: Int): List<User> =
        (1..count).map { n ->
            User(
                id = UserId(n),
                tenantId = workspace.id,
                username = "user$n",
                email = "user$n@acme.example",
                fullName = "User $n",
                passwordHash = "x",
            )
        }

    private fun listPage(
        rows: Int,
        page: Int,
        totalPages: Int,
        totalCount: Long,
        search: String? = null,
    ): String =
        render(
            AdminView.userListPage(
                workspace = workspace,
                users = users(rows),
                allWorkspaces = emptyList(),
                loggedInAs = "admin",
                search = search,
                page = page,
                totalPages = totalPages,
                totalCount = totalCount,
                pageSize = 25,
            ),
        )

    @Test
    fun `a full page reports the range that page actually covers`() {
        val html = listPage(rows = 25, page = 2, totalPages = 4, totalCount = 92)

        assertContains(html, "Showing 26–50 of 92 users")
    }

    @Test
    fun `the short last page still starts where the previous page ended`() {
        // 92 users, 25 per page: page 4 holds 17 rows. Deriving the offset from those 17
        // rows put the range at 52-68 instead of 76-92.
        val html = listPage(rows = 17, page = 4, totalPages = 4, totalCount = 92)

        assertContains(html, "Showing 76–92 of 92 users")
        assertFalse(html.contains("Showing 52"), "The range must not be derived from the rendered row count")
    }

    @Test
    fun `a search term with a separator survives the pagination link`() {
        val html = listPage(rows = 25, page = 1, totalPages = 3, totalCount = 60, search = "a&b c")

        // The ampersand becomes %26 rather than closing the parameter; the space becomes %20.
        assertContains(html, "q=a%26b%20c")
        assertFalse(html.contains("q=a&amp;b c"), "An unencoded term truncates the query string at the ampersand")
    }
}
