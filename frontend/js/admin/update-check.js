/**
 * update-check.js — localStorage-based dismiss for the update chip.
 *
 * On page load, checks if the current update version was previously
 * dismissed. If so, hides the chip immediately. On dismiss click,
 * stores the version in localStorage and hides the chip.
 *
 * Dismissal is version-scoped: a newer release supersedes the
 * prior dismissal automatically.
 */
;(function () {
  'use strict';

  const chip = document.querySelector('.update-chip');
  if (!chip) return;

  const version = chip.getAttribute('data-dismiss-version');
  if (!version) return;

  const dismissed = localStorage.getItem('kauth_update_dismissed');
  if (dismissed === version) {
    chip.classList.add('update-chip--hidden');
    return;
  }

  const btn = chip.querySelector('.update-chip__dismiss');
  if (!btn) return;
  btn.addEventListener('click', () => {
    localStorage.setItem('kauth_update_dismissed', version);
    chip.classList.add('update-chip--hidden');
  });
}());
