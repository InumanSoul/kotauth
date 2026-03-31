/**
 * Auto-dismissing toast notifications.
 *
 * Reads the toast message from a data-toast-msg attribute on <body>.
 * The server renders this attribute when a ?saved= query param is present.
 * After displaying, the query param is cleaned from the URL via replaceState.
 *
 * Falls back gracefully — if JS is disabled, the server-rendered banner
 * (if still present) shows the message as a persistent notice.
 */
(function () {
  'use strict';

  const DEFAULT_DURATION_MS = 5000;
  const FADE_OUT_DURATION_MS = 300;

  document.addEventListener('DOMContentLoaded', () => {
    const message = document.body.getAttribute('data-toast-msg');
    if (!message) return;

    const duration = parseInt(document.body.getAttribute('data-toast-duration') || '0', 10) || DEFAULT_DURATION_MS;
    showToast(message, duration);
    cleanUrl();
  });

  function showToast(message, displayDuration) {
    const toast = document.createElement('div');
    toast.className = 'toast';

    const iconSpan = document.createElement('span');
    iconSpan.className = 'toast__icon';
    iconSpan.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">' +
      '<path d="M20 6L9 17l-5-5"/></svg>';

    const textSpan = document.createElement('span');
    textSpan.textContent = message;

    toast.appendChild(iconSpan);
    toast.appendChild(textSpan);

    // Append to pre-existing ARIA live region if available, otherwise body
    const toastRegion = document.getElementById('toast-region');
    (toastRegion || document.body).appendChild(toast);

    setTimeout(() => {
      toast.classList.add('toast--leaving');
      setTimeout(() => toast.remove(), FADE_OUT_DURATION_MS);
    }, displayDuration);
  }

  function cleanUrl() {
    const url = new URL(window.location.href);
    if (url.searchParams.has('saved') || url.searchParams.has('success')) {
      url.searchParams.delete('saved');
      url.searchParams.delete('success');
      history.replaceState(null, '', url.pathname + url.search);
    }
  }
})();
