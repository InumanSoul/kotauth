/**
 * settings.js — CSP-safe interactions for settings pages.
 *
 * Features:
 *   1. Chip-grid All/None toggles  (data-chips-all, data-chips-none)
 *   2. Chip-grid count updates     (chip-grid change → .chip-grid__count)
 *   3. Copy to clipboard           (data-copy)
 *   4. Confirm dialogs             (data-confirm)
 *
 * All bindings use data-* attributes — zero inline JS.
 */
;(function () {
  'use strict';

  // ── helpers ─────────────────────────────────────────────────
  function updateCount(gridEl) {
    // Walk up to the parent wrapper, then find the count span
    var wrapper = gridEl.closest('div');
    if (!wrapper) return;
    // The header may be a sibling of the grid; look in the shared parent
    var parent = gridEl.parentElement;
    var countEl = parent ? parent.querySelector('.chip-grid__count') : null;
    if (!countEl) return;
    var boxes = gridEl.querySelectorAll('input[type="checkbox"]');
    var checked = gridEl.querySelectorAll('input[type="checkbox"]:checked').length;
    countEl.textContent = checked + ' / ' + boxes.length + ' selected';
  }

  function setAll(gridId, state) {
    var grid = document.getElementById(gridId);
    if (!grid) return;
    grid.querySelectorAll('input[type="checkbox"]').forEach(function (cb) {
      cb.checked = state;
    });
    updateCount(grid);
  }

  // ── chip-grid: All / None buttons ──────────────────────────
  document.addEventListener('click', function (e) {
    var allBtn = e.target.closest('[data-chips-all]');
    if (allBtn) {
      e.preventDefault();
      setAll(allBtn.getAttribute('data-chips-all'), true);
      return;
    }

    var noneBtn = e.target.closest('[data-chips-none]');
    if (noneBtn) {
      e.preventDefault();
      setAll(noneBtn.getAttribute('data-chips-none'), false);
      return;
    }
  });

  // ── chip-grid: live count on checkbox change ───────────────
  document.addEventListener('change', function (e) {
    if (!e.target.matches('.chip-grid input[type="checkbox"]')) return;
    var grid = e.target.closest('.chip-grid');
    if (grid) updateCount(grid);
  });

  // ── copy to clipboard ──────────────────────────────────────
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-copy]');
    if (!btn) return;
    e.preventDefault();
    var text = btn.getAttribute('data-copy');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        var orig = btn.textContent;
        btn.textContent = '\u2713';
        setTimeout(function () { btn.textContent = orig; }, 1500);
      });
    }
  });

  // ── confirm dialogs ────────────────────────────────────────
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-confirm]');
    if (!btn) return;
    var msg = btn.getAttribute('data-confirm');
    if (!window.confirm(msg)) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  });
})();
