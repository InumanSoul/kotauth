/**
 * backup-slug-validation.js — Disable a target submit button until the user
 * has typed an exact slug match into the confirmation input.
 *
 * Mirrors the password-validation.js pattern. Drives off data attributes:
 *   <input data-confirm-slug="acme" data-confirm-target="#backup-export-button" />
 *
 * Progressive enhancement only — server-side and HTML5 `pattern` validation
 * remain authoritative. If JS is disabled the button is enabled and the form
 * fails native validation on submit, which is the existing fallback.
 */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', () => {
    for (const slugInput of document.querySelectorAll('input[data-confirm-slug]')) {
      initGate(slugInput);
    }
  });

  function initGate(slugInput) {
    const expected = slugInput.getAttribute('data-confirm-slug') || '';
    const targetSelector = slugInput.getAttribute('data-confirm-target');
    if (!targetSelector) return;
    const button = document.querySelector(targetSelector);
    if (!button) return;

    const sync = () => {
      const matches = slugInput.value === expected;
      button.disabled = !matches;
      button.setAttribute('aria-disabled', matches ? 'false' : 'true');
    };

    sync();
    slugInput.addEventListener('input', sync);
  }
})();
