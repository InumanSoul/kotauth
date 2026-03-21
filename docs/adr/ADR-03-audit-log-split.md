# ADR-03: Audit Log Split — Write Port vs Read Repository

**Status:** Accepted

## Decision

`AuditLogPort` (write-only, fire-and-forget, implemented by `PostgresAuditLogAdapter`) is separate from `AuditLogRepository` (read-only, paginated queries, implemented by `PostgresAuditLogRepository`).

## Consequence

Auth-path audit writes never block on query logic. Admin console reads never share a code path with hot auth flows.
