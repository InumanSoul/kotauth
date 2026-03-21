# ADR-04: Admin-Mutating Operations via `AdminService`, Not Direct Repositories

**Status:** Accepted

## Decision

All admin write operations (create user, update workspace, regenerate secret, etc.) go through `AdminService` rather than calling repositories directly from routes.

## Consequence

Every mutation records an audit event in the same call. Validation and business rules live in one place. Routes stay thin.
