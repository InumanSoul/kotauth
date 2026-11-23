/**
 * Second-factor setup during sign-in: draws the QR code from data-totp-uri and copies the
 * recovery codes. The pages work without it apart from the QR code; the key is printed too.
 */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', () => {
    const qr = document.getElementById('mfa-qr');
    if (qr && qr.dataset.totpUri && typeof QRCode !== 'undefined') {
      new QRCode(qr, {
        text: qr.dataset.totpUri,
        width: 184,
        height: 184,
        colorDark: '#000000',
        colorLight: '#ffffff',
        correctLevel: QRCode.CorrectLevel.M,
      });
    }

    const copy = document.querySelector('[data-action="copy-recovery-codes"]');
    const codes = document.getElementById('mfa-recovery-codes');
    if (copy && codes && navigator.clipboard) {
      copy.hidden = false;
      const label = copy.textContent;
      copy.addEventListener('click', () => {
        const text = Array.from(codes.querySelectorAll('code')).map((c) => c.textContent).join('\n');
        navigator.clipboard.writeText(text).then(() => {
          copy.textContent = copy.dataset.copiedLabel || label;
          setTimeout(() => { copy.textContent = label; }, 2000);
        });
      });
    }
  });
})();
