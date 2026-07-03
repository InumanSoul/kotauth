// frontend/js/passkeys.js
(function () {
  const Kotauth = window.Kotauth = window.Kotauth || {};

  function b64uToBytes(b64u) {
    const b64 = (b64u + '==='.slice((b64u.length + 3) % 4)).replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes.buffer;
  }

  function bytesToB64u(buf) {
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function decodeCreateOptions(json) {
    const options = json.publicKey || json;
    options.challenge = b64uToBytes(options.challenge);
    options.user.id = b64uToBytes(options.user.id);
    if (options.excludeCredentials) {
      options.excludeCredentials = options.excludeCredentials.map(c => ({ ...c, id: b64uToBytes(c.id) }));
    }
    return options;
  }

  function decodeGetOptions(json) {
    const options = json.publicKey || json;
    options.challenge = b64uToBytes(options.challenge);
    if (options.allowCredentials) {
      options.allowCredentials = options.allowCredentials.map(c => ({ ...c, id: b64uToBytes(c.id) }));
    }
    return options;
  }

  function serializeAttestation(credential) {
    return {
      id: credential.id,
      rawId: bytesToB64u(credential.rawId),
      type: credential.type,
      response: {
        clientDataJSON: bytesToB64u(credential.response.clientDataJSON),
        attestationObject: bytesToB64u(credential.response.attestationObject),
        transports: credential.response.getTransports ? credential.response.getTransports() : [],
      },
      clientExtensionResults: credential.getClientExtensionResults ? credential.getClientExtensionResults() : {},
    };
  }

  function serializeAssertion(credential) {
    return {
      id: credential.id,
      rawId: bytesToB64u(credential.rawId),
      type: credential.type,
      response: {
        clientDataJSON: bytesToB64u(credential.response.clientDataJSON),
        authenticatorData: bytesToB64u(credential.response.authenticatorData),
        signature: bytesToB64u(credential.response.signature),
        userHandle: credential.response.userHandle ? bytesToB64u(credential.response.userHandle) : null,
      },
      clientExtensionResults: credential.getClientExtensionResults ? credential.getClientExtensionResults() : {},
    };
  }

  async function enrollPasskey(basePath, name) {
    const startResp = await fetch(basePath + '/register/start', { method: 'POST', credentials: 'include' });
    if (!startResp.ok) throw new Error('start failed');
    const optionsJson = await startResp.json();
    const options = decodeCreateOptions(optionsJson);
    const credential = await navigator.credentials.create({ publicKey: options });
    const finishBody = { credential: serializeAttestation(credential), name };
    const finishResp = await fetch(basePath + '/register/finish', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(finishBody),
    });
    if (!finishResp.ok) throw new Error('finish failed');
    return await finishResp.json();
  }

  async function signInWithPasskey(basePath, oauthContext) {
    const startResp = await fetch(basePath + '/authenticate/start', { method: 'POST', credentials: 'include' });
    if (!startResp.ok) throw new Error('start failed');
    const optionsJson = await startResp.json();
    const options = decodeGetOptions(optionsJson);
    const credential = await navigator.credentials.get({ publicKey: options });
    const finishBody = { credential: serializeAssertion(credential) };
    if (oauthContext) finishBody.oauth_context = oauthContext;
    const finishResp = await fetch(basePath + '/authenticate/finish', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(finishBody),
    });
    if (!finishResp.ok) throw new Error('finish failed');
    if (finishResp.redirected) window.location.assign(finishResp.url);
    else window.location.assign('/');
  }

  async function startConditionalMediation(basePath) {
    if (!window.PublicKeyCredential) return;
    if (typeof PublicKeyCredential.isConditionalMediationAvailable !== 'function') return;
    if (!(await PublicKeyCredential.isConditionalMediationAvailable())) return;
    try {
      const startResp = await fetch(basePath + '/authenticate/start', { method: 'POST', credentials: 'include' });
      if (!startResp.ok) return;
      const optionsJson = await startResp.json();
      const options = decodeGetOptions(optionsJson);
      const credential = await navigator.credentials.get({ publicKey: options, mediation: 'conditional' });
      const finishResp = await fetch(basePath + '/authenticate/finish', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ credential: serializeAssertion(credential) }),
      });
      if (finishResp.ok) window.location.assign(finishResp.redirected ? finishResp.url : '/');
    } catch (e) {
      // Silent — autofill failure is a UX no-op.
    }
  }

  Kotauth.passkeys = { enrollPasskey, signInWithPasskey, startConditionalMediation };
})();
