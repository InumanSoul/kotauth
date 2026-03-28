/**
 * auth.js — CSP-safe interactions for auth pages (login, register, reset-password).
 *
 * Features:
 *   1. Password show/hide toggle (data-toggle-password)
 *
 * All bindings use data-* attributes — zero inline JS.
 * Shared confirm-dialog.js is loaded separately if a <dialog> is present.
 */
;(function () {
  'use strict';

  // ── password show/hide toggle ──────────────────────────────
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-toggle-password]');
    if (!btn) return;
    e.preventDefault();

    var targetId = btn.getAttribute('data-toggle-password');
    var input = document.getElementById(targetId);
    if (!input) return;

    var isVisible = btn.getAttribute('data-visible') === 'true';
    if (isVisible) {
      input.type = 'password';
      btn.setAttribute('data-visible', 'false');
      btn.setAttribute('aria-label', 'Show password');
      btn.setAttribute('aria-pressed', 'false');
    } else {
      input.type = 'text';
      btn.setAttribute('data-visible', 'true');
      btn.setAttribute('aria-label', 'Hide password');
      btn.setAttribute('aria-pressed', 'true');
    }
  });
})();
