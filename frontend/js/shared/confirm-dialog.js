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

  var dialog = document.getElementById('confirm-dialog');
  var dialogMsg = document.getElementById('confirm-dialog-message');
  var dialogOk = document.getElementById('confirm-dialog-ok');
  var dialogCancel = document.getElementById('confirm-dialog-cancel');
  var pendingResolve = null;

  function showConfirm(message) {
    return new Promise(function (resolve) {
      if (!dialog) { resolve(false); return; }
      dialogMsg.textContent = message;
      pendingResolve = resolve;
      dialog.showModal();
    });
  }

  if (dialog) {
    dialogOk.addEventListener('click', function () {
      dialog.close();
      if (pendingResolve) { pendingResolve(true); pendingResolve = null; }
    });
    dialogCancel.addEventListener('click', function () {
      dialog.close();
      if (pendingResolve) { pendingResolve(false); pendingResolve = null; }
    });
    dialog.addEventListener('close', function () {
      if (pendingResolve) { pendingResolve(false); pendingResolve = null; }
    });
  }

  // data-confirm on regular buttons/forms
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-confirm]');
    if (!btn) return;
    var msg = btn.getAttribute('data-confirm');
    e.preventDefault();
    e.stopImmediatePropagation();
    showConfirm(msg).then(function (ok) {
      if (ok) {
        btn.removeAttribute('data-confirm');
        btn.click();
        setTimeout(function () { btn.setAttribute('data-confirm', msg); }, 0);
      }
    });
  }, true);

  // htmx:confirm for hx-confirm attributes (only if htmx is present)
  if (typeof htmx !== 'undefined') {
    document.addEventListener('htmx:confirm', function (e) {
      // Only intercept when hx-confirm is set (question is non-empty)
      if (!e.detail.question) return;
      e.preventDefault();
      showConfirm(e.detail.question).then(function (ok) {
        if (ok) e.detail.issueRequest();
      });
    });
  }
})();
