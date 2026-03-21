# ADR-02: Flyway for Schema Migrations

**Status:** Accepted

## Decision

Replaced `SchemaUtils.createMissingTablesAndColumns` with Flyway versioned migrations (V1–V21) before Phase 1.

## Consequence

Schema changes are versioned, reversible, and safe to run in CI/CD. No schema drift between dev and production.
