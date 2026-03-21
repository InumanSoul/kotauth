# ADR-06: `AdminResult<T>` Instead of Throwing Exceptions

**Status:** Accepted

## Decision

`AdminService` methods return `AdminResult.Success<T>` or `AdminResult.Failure(AdminError)`. Routes pattern-match on the result.

## Consequence

Error handling is explicit and exhaustive. No try/catch in routes. `AdminError` subtypes (`NotFound`, `Conflict`, `Validation`) map cleanly to HTTP 404/409/422.
