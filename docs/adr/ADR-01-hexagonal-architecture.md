# ADR-01: Hexagonal (Ports & Adapters) Architecture

**Status:** Accepted

## Decision

All domain logic in `domain/` has zero framework dependencies. Adapters in `adapter/` implement domain ports. The composition root (`Application.kt`) wires everything.

## Consequence

Adding a new persistence layer (e.g., MySQL) means writing a new adapter, not touching domain logic. Domain services can be tested with no Ktor/Exposed imports.
