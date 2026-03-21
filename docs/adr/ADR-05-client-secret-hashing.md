# ADR-05: Client Secret — Raw Value Never Stored

**Status:** Accepted

## Decision

`regenerateClientSecret` generates a 32-byte CSPRNG secret, stores only its bcrypt hash, and returns the raw value once. The raw value is passed via a URL query parameter `?newSecret=...` and shown once in the admin UI with a "copy now" warning.

## Consequence

Even a full database dump reveals no usable client secrets. The trade-off is that a lost secret requires regeneration (not recovery).
