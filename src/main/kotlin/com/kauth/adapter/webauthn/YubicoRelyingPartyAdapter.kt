package com.kauth.adapter.webauthn

import com.yubico.webauthn.RelyingParty

class YubicoRelyingPartyAdapter(
    private val relyingParty: RelyingParty,
) : RelyingPartyAdapter
