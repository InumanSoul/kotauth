package com.kauth.adapter.web.admin

import io.ktor.server.routing.*

/*
 * Admin module placeholder.
 *
 * When the time comes, this module will expose /admin/... routes protected by
 * JWT auth middleware (admin role claim). It will reuse the same AuthService
 * and UserRepository ports from the domain -- no new persistence needed.
 *
 * Planned routes:
 *   GET  /admin/users                       list all users
 *   GET  /admin/users/{id}                  user detail
 *   PUT  /admin/users/{id}                  update user (role, status)
 *   DEL  /admin/users/{id}                  deactivate user
 *   GET  /admin/sessions                    active sessions
 *   POST /admin/users/{id}/reset-password   force password reset
 */
fun Route.adminRoutes() {
    // TODO: Implement admin module
}
