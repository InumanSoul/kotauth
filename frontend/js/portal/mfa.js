/**
 * MFA challenge and enrollment interactions for the self-service portal.
 *
 * MFA challenge: toggles between TOTP code input and recovery code input.
 * MFA enrollment: drives the multi-step TOTP setup flow (enroll → QR → verify → done).
 */
(function () {
  'use strict';

  // ── MFA challenge — recovery mode toggle ────────────────────────────────

  let recoveryMode = false;

  window.toggleRecoveryMode = function () {
    recoveryMode = !recoveryMode;
    const codeInput = document.getElementById('code');
    const codeLabel = document.getElementById('code-label');
    const subtitle = document.getElementById('challenge-subtitle');
    const toggleLink = document.getElementById('recovery-toggle');

    if (recoveryMode) {
      codeLabel.textContent = 'Recovery code';
      subtitle.textContent = 'Enter one of the 8-character recovery codes you saved during setup';
      toggleLink.textContent = 'Use authenticator app instead';
      codeInput.placeholder = 'e.g. a1b2c3d4';
      codeInput.removeAttribute('inputmode');
      codeInput.removeAttribute('pattern');
      codeInput.removeAttribute('maxlength');
    } else {
      codeLabel.textContent = 'Verification code';
      subtitle.textContent = 'Enter the 6-digit code from your authenticator app';
      toggleLink.textContent = 'Use a recovery code instead';
      codeInput.placeholder = '000000';
      codeInput.setAttribute('inputmode', 'numeric');
      codeInput.setAttribute('pattern', '[0-9]*');
      codeInput.setAttribute('maxlength', '6');
    }
    codeInput.value = '';
    codeInput.focus();
  };

  // ── MFA enrollment — multi-step setup ───────────────────────────────────

  let savedRecoveryCodes = null;

  window.startEnrollment = async function (slug) {
    const startButton = document.getElementById('start-btn');
    const errorElement = document.getElementById('enroll-error');
    startButton.disabled = true;
    startButton.textContent = 'Setting up\u2026';
    errorElement.style.display = 'none';

    try {
      const response = await fetch('/t/' + slug + '/account/mfa/enroll', { method: 'POST' });
      const data = await response.json();

      if (!response.ok) {
        errorElement.textContent = data.error === 'already_enrolled'
          ? 'An authenticator is already configured. Refresh the page.'
          : 'Failed to start setup. Please try again.';
        errorElement.style.display = 'block';
        startButton.disabled = false;
        startButton.textContent = 'Set up authenticator';
        return;
      }

      document.getElementById('mfa-step-1').style.display = 'none';
      document.getElementById('mfa-step-2').style.display = 'block';

      new QRCode(document.getElementById('qr-code'), {
        text: data.totp_uri,
        width: 200,
        height: 200,
        colorDark: '#000000',
        colorLight: '#ffffff',
        correctLevel: QRCode.CorrectLevel.M,
      });

      const secretMatch = data.totp_uri.match(/secret=([A-Z2-7]+)/i);
      if (secretMatch) {
        document.getElementById('setup-key').textContent = secretMatch[1];
      }

      savedRecoveryCodes = data.recovery_codes;
      const codesGrid = document.getElementById('recovery-codes');
      codesGrid.innerHTML = '';
      for (const code of data.recovery_codes) {
        const codeElement = document.createElement('span');
        codeElement.className = 'recovery-code';
        codeElement.textContent = code;
        codesGrid.appendChild(codeElement);
      }
    } catch (_error) {
      errorElement.textContent = 'Network error. Please check your connection and try again.';
      errorElement.style.display = 'block';
      startButton.disabled = false;
      startButton.textContent = 'Set up authenticator';
    }
  };

  window.verifyEnrollment = async function (slug) {
    const totpCode = document.getElementById('totp-code').value.trim();
    const errorElement = document.getElementById('verify-error');
    const verifyButton = document.getElementById('verify-btn');
    errorElement.style.display = 'none';

    if (!/^\d{6}$/.test(totpCode)) {
      errorElement.textContent = 'Please enter the 6-digit code from your authenticator app.';
      errorElement.style.display = 'block';
      return;
    }

    verifyButton.disabled = true;
    verifyButton.textContent = 'Verifying\u2026';

    try {
      const body = new URLSearchParams({ code: totpCode });
      const response = await fetch('/t/' + slug + '/account/mfa/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body,
      });
      const data = await response.json();

      if (response.ok) {
        window.location.href = '/t/' + slug + '/account/mfa?success=true';
      } else {
        errorElement.textContent = data.error === 'invalid_code'
          ? 'Incorrect code. Check your device clock is accurate and try again.'
          : 'Verification failed. Please try again.';
        errorElement.style.display = 'block';
        verifyButton.disabled = false;
        verifyButton.textContent = 'Confirm setup';
      }
    } catch (_error) {
      errorElement.textContent = 'Network error. Please try again.';
      errorElement.style.display = 'block';
      verifyButton.disabled = false;
      verifyButton.textContent = 'Confirm setup';
    }
  };

  window.disableMfa = async function (slug) {
    const disableButton = document.getElementById('disable-btn');
    disableButton.disabled = true;
    disableButton.textContent = 'Removing\u2026';

    try {
      const response = await fetch('/t/' + slug + '/account/mfa/disable', { method: 'POST' });
      if (response.ok) {
        window.location.reload();
      } else {
        disableButton.disabled = false;
        disableButton.textContent = 'Yes, remove authenticator';
      }
    } catch (_error) {
      disableButton.disabled = false;
      disableButton.textContent = 'Yes, remove authenticator';
    }
  };

  window.copyCodes = function () {
    if (!savedRecoveryCodes) return;
    navigator.clipboard.writeText(savedRecoveryCodes.join('\n')).then(function () {
      const copyButton = document.getElementById('copy-codes-btn');
      copyButton.textContent = 'Copied!';
      setTimeout(function () { copyButton.textContent = 'Copy codes'; }, 2000);
    });
  };
})();
