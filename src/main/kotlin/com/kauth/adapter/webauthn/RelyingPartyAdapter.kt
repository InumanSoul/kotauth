package com.kauth.adapter.webauthn

/**
 * Isolates Yubico webauthn-server-core types from the domain layer.
 * Concrete implementation ([YubicoRelyingPartyAdapter]) wraps [com.yubico.webauthn.RelyingParty].
 * Method signatures added in Task 4.
 */
interface RelyingPartyAdapter
