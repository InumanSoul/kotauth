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
    if (!credential) {
      throw new Error('Passkey enrollment cancelled');
    }
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
    if (!credential) {
      throw new Error('Passkey sign-in cancelled');
    }
    const finishBody = { credential: serializeAssertion(credential) };
    if (oauthContext) finishBody.oauth_context = oauthContext;
    const finishResp = await fetch(basePath + '/authenticate/finish', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(finishBody),
    });
    if (!finishResp.ok) throw new Error('finish failed');
    const result = await finishResp.json();
    if (result.redirect_url) {
      window.location.assign(result.redirect_url);
    } else if (result.user_id) {
      window.location.assign('/');
    } else {
      throw new Error('Unexpected finish response');
    }
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
      if (!credential) return; // user dismissed autofill, no-op
      const finishResp = await fetch(basePath + '/authenticate/finish', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ credential: serializeAssertion(credential) }),
      });
      if (finishResp.ok) {
        const finishBody = await finishResp.json();
        window.location.assign(finishBody.redirect_url || '/');
      }
    } catch (e) {
      // autofill failure is silent
    }
  }

  function resolveErrorMessage(serverErrorType, strings) {
    var map = {
      'AlreadyEnrolled': strings.errorAlreadyEnrolled,
      'VerificationFailed': strings.errorVerification,
    };
    return map[serverErrorType] || strings.errorGeneric;
  }

  function classifyException(err, strings) {
    if (!window.PublicKeyCredential) return strings.errorUnsupported;
    var msg = (err && err.message) ? err.message.toLowerCase() : '';
    if (msg.indexOf('cancel') !== -1 || msg.indexOf('abort') !== -1 || msg.indexOf('not allowed') !== -1) {
      return strings.errorCancelled;
    }
    return strings.errorGeneric;
  }

  function showError(message) {
    var el = document.getElementById('passkey-error');
    if (!el) return;
    el.textContent = message;
    el.hidden = false;
  }

  function clearError() {
    var el = document.getElementById('passkey-error');
    if (!el) return;
    el.textContent = '';
    el.hidden = true;
  }

  Kotauth.passkeys = {
    enrollPasskey,
    signInWithPasskey,
    startConditionalMediation,
    showError,
    clearError,
  };

  var scriptEl = document.currentScript;
  if (scriptEl) {
    var mode = scriptEl.getAttribute('data-passkey-mode');
    var base = scriptEl.getAttribute('data-passkey-base');

    var strings = {
      errorGeneric:        scriptEl.getAttribute('data-passkey-error-generic')          || 'We couldn’t complete that. Please try again.',
      errorCancelled:      scriptEl.getAttribute('data-passkey-error-cancelled')         || 'Passkey action was cancelled.',
      errorVerification:   scriptEl.getAttribute('data-passkey-error-verification')      || 'Your device could not be verified. Try a different passkey.',
      errorAlreadyEnrolled: scriptEl.getAttribute('data-passkey-error-already-enrolled') || 'That passkey is already registered on this account.',
      errorUnsupported:    scriptEl.getAttribute('data-passkey-error-unsupported')       || 'This browser doesn’t support passkeys.',
    };

    if (mode === 'login' && base) {
      document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.passkey-signin-btn').forEach(function (btn) {
          btn.addEventListener('click', async function (e) {
            e.preventDefault();
            clearError();
            try {
              await Kotauth.passkeys.signInWithPasskey(base);
            } catch (err) {
              showError(classifyException(err, strings));
            }
          });
        });
        Kotauth.passkeys.startConditionalMediation(base);
      });
    }

    if (mode === 'manage' && base) {
      var addTitle    = scriptEl.getAttribute('data-passkey-add-title')    || 'Add a passkey';
      var renameTitle = scriptEl.getAttribute('data-passkey-rename-title') || 'Rename passkey';

      function openNameDialog(title, initialValue) {
        return new Promise(function (resolve) {
          var dlg       = document.getElementById('passkey-name-dialog');
          var titleEl   = document.getElementById('passkey-name-dialog-title');
          var input     = document.getElementById('passkey-name-dialog-input');
          var saveBtn   = document.getElementById('passkey-name-dialog-save');
          var cancelBtn = document.getElementById('passkey-name-dialog-cancel');
          if (!dlg) { resolve(null); return; }

          titleEl.textContent = title;
          input.value = initialValue || '';

          function onSave() {
            var value = input.value.trim();
            cleanup();
            dlg.close();
            resolve(value || null);
          }
          function onCancel() {
            cleanup();
            dlg.close();
            resolve(null);
          }
          function onClose() {
            cleanup();
            resolve(null);
          }
          function cleanup() {
            saveBtn.removeEventListener('click', onSave);
            cancelBtn.removeEventListener('click', onCancel);
            dlg.removeEventListener('close', onClose);
          }

          saveBtn.addEventListener('click', onSave);
          cancelBtn.addEventListener('click', onCancel);
          dlg.addEventListener('close', onClose, { once: true });
          dlg.showModal();
          setTimeout(function () { input.focus(); }, 0);
        });
      }

      document.addEventListener('DOMContentLoaded', function () {
        var addBtn = document.getElementById('add-passkey-btn');
        if (addBtn) {
          addBtn.addEventListener('click', function () {
            openNameDialog(addTitle, '').then(function (name) {
              if (!name) return;
              clearError();
              Kotauth.passkeys.enrollPasskey(base, name).then(function () {
                window.location.reload();
              }).catch(function (err) {
                showError(classifyException(err, strings));
              });
            });
          });
        }

        document.querySelectorAll('.passkey-rename-btn').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var currentName = btn.getAttribute('data-passkey-name') || '';
            var id          = btn.getAttribute('data-passkey-id');
            openNameDialog(renameTitle, currentName).then(function (newName) {
              if (!newName || newName === currentName) return;
              clearError();
              fetch(base + '/' + id + '/rename', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: newName }),
              }).then(function () {
                window.location.reload();
              }).catch(function (err) {
                showError(classifyException(err, strings));
              });
            });
          });
        });

        document.querySelectorAll('.passkey-revoke-btn').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var id = btn.getAttribute('data-passkey-id');
            fetch(base + '/' + id + '/revoke', { method: 'POST', credentials: 'include' })
              .then(function () { window.location.reload(); })
              .catch(function (err) { showError(classifyException(err, strings)); });
          });
        });
      });
    }

    if (mode === 'enroll' && base) {
      var redirect    = scriptEl.getAttribute('data-passkey-redirect') || '/';
      var defaultName = scriptEl.getAttribute('data-passkey-default-name') || 'This device';
      document.addEventListener('DOMContentLoaded', function () {
        var enrollBtn = document.getElementById('enroll-passkey-btn');
        if (enrollBtn) {
          enrollBtn.addEventListener('click', async function () {
            clearError();
            try {
              await Kotauth.passkeys.enrollPasskey(base, defaultName);
              window.location.assign(redirect);
            } catch (err) {
              showError(classifyException(err, strings));
            }
          });
        }
      });
    }
  }
})();
