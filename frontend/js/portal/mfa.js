/**
 * MFA challenge and enrollment interactions for the self-service portal.
 *
 * All interactions use data-action attributes + event delegation (CSP-compliant).
 * The tenant slug is read once from data-tenant-slug on the page container.
 */
(function () {
  'use strict';

  let recoveryMode = false;
  let savedRecoveryCodes = null;

  document.addEventListener('DOMContentLoaded', () => {
    const slugElement = document.querySelector('[data-tenant-slug]');
    const slug = slugElement ? slugElement.getAttribute('data-tenant-slug') : null;

    document.addEventListener('click', (event) => {
      const target = event.target.closest('[data-action]');
      if (!target) return;

      const action = target.getAttribute('data-action');

      switch (action) {
        case 'toggle-recovery':
          event.preventDefault();
          toggleRecoveryMode();
          break;
        case 'start-enrollment':
          startEnrollment(slug, target);
          break;
        case 'verify-enrollment':
          verifyEnrollment(slug, target);
          break;
        case 'copy-codes':
          copyCodes(target);
          break;
        case 'show-disable-confirm':
          showElement('disable-confirm');
          hideElement('disable-btn-row');
          break;
        case 'hide-disable-confirm':
          hideElement('disable-confirm');
          showElement('disable-btn-row');
          break;
        case 'disable-mfa':
          disableMfa(slug, target);
          break;
        case 'toggle-delete-confirm':
          document.getElementById('delete-confirm')?.classList.toggle('is-open');
          break;
      }
    });

    document.addEventListener('change', (event) => {
      const target = event.target.closest('[data-action="codes-saved-toggle"]');
      if (!target) return;
      const step2b = document.getElementById('mfa-step-2b');
      if (step2b) step2b.style.display = target.checked ? 'block' : 'none';
    });
  });

  function showElement(id) {
    const element = document.getElementById(id);
    if (element) element.style.display = 'block';
  }

  function hideElement(id) {
    const element = document.getElementById(id);
    if (element) element.style.display = 'none';
  }

  // ── MFA challenge — recovery mode toggle ────────────────────────────────

  function toggleRecoveryMode() {
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
  }

  // ── MFA enrollment — multi-step setup ───────────────────────────────────

  async function startEnrollment(slug, button) {
    const errorElement = document.getElementById('enroll-error');
    button.disabled = true;
    button.textContent = 'Setting up\u2026';
    errorElement.style.display = 'none';

    try {
      const response = await fetch('/t/' + slug + '/account/mfa/enroll', { method: 'POST' });
      const data = await response.json();

      if (!response.ok) {
        errorElement.textContent = data.error === 'already_enrolled'
          ? 'An authenticator is already configured. Refresh the page.'
          : 'Failed to start setup. Please try again.';
        errorElement.style.display = 'block';
        button.disabled = false;
        button.textContent = 'Set up authenticator';
        return;
      }

      hideElement('mfa-step-1');
      showElement('mfa-step-2');

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
      button.disabled = false;
      button.textContent = 'Set up authenticator';
    }
  }

  async function verifyEnrollment(slug, button) {
    const totpCode = document.getElementById('totp-code').value.trim();
    const errorElement = document.getElementById('verify-error');
    errorElement.style.display = 'none';

    if (!/^\d{6}$/.test(totpCode)) {
      errorElement.textContent = 'Please enter the 6-digit code from your authenticator app.';
      errorElement.style.display = 'block';
      return;
    }

    button.disabled = true;
    button.textContent = 'Verifying\u2026';

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
        button.disabled = false;
        button.textContent = 'Confirm setup';
      }
    } catch (_error) {
      errorElement.textContent = 'Network error. Please try again.';
      errorElement.style.display = 'block';
      button.disabled = false;
      button.textContent = 'Confirm setup';
    }
  }

  async function disableMfa(slug, button) {
    button.disabled = true;
    button.textContent = 'Removing\u2026';

    try {
      const response = await fetch('/t/' + slug + '/account/mfa/disable', { method: 'POST' });
      if (response.ok) {
        window.location.reload();
      } else {
        button.disabled = false;
        button.textContent = 'Yes, remove authenticator';
      }
    } catch (_error) {
      button.disabled = false;
      button.textContent = 'Yes, remove authenticator';
    }
  }

  function copyCodes(button) {
    if (!savedRecoveryCodes) return;
    navigator.clipboard.writeText(savedRecoveryCodes.join('\n')).then(() => {
      button.textContent = 'Copied!';
      setTimeout(() => { button.textContent = 'Copy codes'; }, 2000);
    });
  }
})();
