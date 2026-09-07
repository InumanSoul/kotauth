-- =============================================================================
-- V64: Identity Providers — trust the issuer's email claim
--
-- Just-in-time provisioning refuses unless the ID token says the address is
-- verified. An absent `email_verified` claim reads as false, and several major
-- issuers — Microsoft Entra ID among them — never emit the claim at all, so JIT
-- could not provision from them however the domain allowlist was set.
--
-- Additive and default FALSE: every existing row keeps the strict behaviour, and
-- an operator opts in per provider. The domain allowlist still applies on top,
-- so trusting the claim widens who may be created, never to whom.
-- =============================================================================

ALTER TABLE identity_providers
    ADD COLUMN trust_email_claim BOOLEAN NOT NULL DEFAULT FALSE;
