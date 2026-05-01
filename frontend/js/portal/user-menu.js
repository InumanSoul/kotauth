/**
 * Portal user-menu dropdown — toggles the avatar/username panel.
 *
 * Pure event delegation, no listeners attached per-element. Closes on:
 *   - second click of the trigger
 *   - click outside the panel/trigger
 *   - Escape key
 */
(function () {
  'use strict';

  function closeAllMenus() {
    document.querySelectorAll('[data-user-menu]').forEach((root) => {
      const trigger = root.querySelector('.portal-user-menu__trigger');
      const panel = root.querySelector('.portal-user-menu__panel');
      if (!trigger || !panel) return;
      root.classList.remove('is-open');
      trigger.setAttribute('aria-expanded', 'false');
      panel.setAttribute('hidden', 'hidden');
    });
  }

  document.addEventListener('click', (event) => {
    const trigger = event.target.closest('[data-action="toggle-user-menu"]');
    if (trigger) {
      event.preventDefault();
      const root = trigger.closest('[data-user-menu]');
      if (!root) return;
      const panel = root.querySelector('.portal-user-menu__panel');
      if (!panel) return;
      const wasOpen = root.classList.contains('is-open');
      closeAllMenus();
      if (!wasOpen) {
        root.classList.add('is-open');
        trigger.setAttribute('aria-expanded', 'true');
        panel.removeAttribute('hidden');
      }
      return;
    }

    if (!event.target.closest('[data-user-menu]')) {
      closeAllMenus();
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeAllMenus();
  });
})();
