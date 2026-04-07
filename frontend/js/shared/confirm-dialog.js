/**
 * confirm-dialog.js — Custom confirmation dialog using <dialog>.
 *
 * Replaces browser confirm() with a styled modal for both:
 *   1. data-confirm attributes (regular form buttons)
 *   2. htmx:confirm events (hx-confirm attributes)
 *
 * Requires a <dialog id="confirm-dialog"> element in the page.
 * If the dialog element is missing, destructive actions are blocked (no fallback).
 *
 * Shared between admin console and self-service portal.
 */
;(function () {
  'use strict';

  const dialog = document.getElementById('confirm-dialog');
  const dialogMsg = document.getElementById('confirm-dialog-message');
  const dialogOk = document.getElementById('confirm-dialog-ok');
  const dialogCancel = document.getElementById('confirm-dialog-cancel');
  let pendingResolve = null;

  const showConfirm = (message) => new Promise((resolve) => {
    if (!dialog) { resolve(false); return; }
    dialogMsg.textContent = message;
    pendingResolve = resolve;
    dialog.showModal();
  });

  if (dialog) {
    dialogOk.addEventListener('click', () => {
      dialog.close();
      if (pendingResolve) { pendingResolve(true); pendingResolve = null; }
    });
    dialogCancel.addEventListener('click', () => {
      dialog.close();
      if (pendingResolve) { pendingResolve(false); pendingResolve = null; }
    });
    dialog.addEventListener('close', () => {
      if (pendingResolve) { pendingResolve(false); pendingResolve = null; }
    });
  }

  // data-confirm on regular buttons/forms
  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-confirm]');
    if (!btn) return;
    const msg = btn.getAttribute('data-confirm');
    e.preventDefault();
    e.stopImmediatePropagation();
    showConfirm(msg).then((ok) => {
      if (ok) {
        btn.removeAttribute('data-confirm');
        btn.click();
        setTimeout(() => { btn.setAttribute('data-confirm', msg); }, 0);
      }
    });
  }, true);

  // htmx:confirm for hx-confirm attributes (only if htmx is present)
  if (typeof htmx !== 'undefined') {
    document.addEventListener('htmx:confirm', (e) => {
      if (!e.detail.question) return;
      e.preventDefault();
      showConfirm(e.detail.question).then((ok) => {
        if (ok) e.detail.issueRequest();
      });
    });
  }
})();
