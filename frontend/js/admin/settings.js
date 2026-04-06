/**
 * settings.js — CSP-safe interactions for admin pages.
 *
 * Features:
 *   1. Chip-grid All/None toggles  (data-chips-all, data-chips-none)
 *   2. Chip-grid count updates     (chip-grid change → .chip-grid__count)
 *   3. Copy to clipboard           (data-copy)
 *   4. Scope toggle (show/hide)    (data-scope-toggle)
 *   5. Auto-submit on change       (data-autosubmit)
 *   6. Save button feedback         (btn--primary in forms → "Saving…" on submit)
 *
 * Confirm dialogs are handled by confirm-dialog.js (shared with portal).
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
  var CHECK_SVG = '<svg width="13" height="13" viewBox="0 0 15 15" fill="none" xmlns="http://www.w3.org/2000/svg">' +
    '<path d="M3 8l3 3 6-7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-copy]');
    if (!btn) return;
    e.preventDefault();
    var text = btn.getAttribute('data-copy');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        var origHtml = btn.innerHTML;
        btn.innerHTML = CHECK_SVG;
        btn.setAttribute('data-copied', '');
        setTimeout(function () {
          btn.innerHTML = origHtml;
          btn.removeAttribute('data-copied');
        }, 1500);
      });
    }
  });

  // ── scope toggle (show/hide field by select value) ────────
  document.addEventListener('change', function (e) {
    var sel = e.target.closest('[data-scope-toggle]');
    if (!sel) return;
    var targetId = sel.getAttribute('data-scope-toggle');
    var target = document.getElementById(targetId);
    if (!target) return;
    target.style.display = sel.value === 'client' ? '' : 'none';
  });

  // ── auto-submit on change ─────────────────────────────────
  document.addEventListener('change', function (e) {
    var el = e.target.closest('[data-autosubmit]');
    if (!el || !el.form) return;
    el.form.submit();
  });

  // ── save button feedback ──────────────────────────────────
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.tagName !== 'FORM') return;
    var btn = form.querySelector('button[type="submit"].btn--primary');
    if (!btn) return;
    btn.disabled = true;
    btn.setAttribute('data-original-text', btn.textContent);
    btn.textContent = 'Saving\u2026';
  });

  // ── entity-picker: keyboard navigation ──────────────────
  function pickerSetFocused(input, items, target) {
    items.forEach(function (item) { item.classList.remove('entity-picker__item--focused'); });
    if (!target) return;
    target.classList.add('entity-picker__item--focused');
    input.setAttribute('aria-activedescendant', target.id || '');
    target.scrollIntoView({ block: 'nearest' });
  }

  function pickerClear(input, dropdown) {
    input.value = '';
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-activedescendant', '');
    dropdown.innerHTML = '';
  }

  document.addEventListener('keydown', function (e) {
    var input = e.target.closest('.entity-picker__input');
    if (!input) return;
    var picker = input.closest('.entity-picker');
    var dropdown = picker ? picker.querySelector('.entity-picker__dropdown') : null;
    if (!dropdown) return;
    var items = Array.from(dropdown.querySelectorAll('.entity-picker__item'));
    if (!items.length) return;
    var focused = dropdown.querySelector('.entity-picker__item--focused');
    var idx = focused ? items.indexOf(focused) : -1;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      pickerSetFocused(input, items, items[idx + 1] || items[0]);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      pickerSetFocused(input, items, items[idx - 1] || items[items.length - 1]);
    } else if (e.key === 'Enter' && focused) {
      e.preventDefault();
      var btn = focused.querySelector('[data-entity-picker-item]');
      if (btn) btn.click();
    } else if (e.key === 'Escape') {
      pickerClear(input, dropdown);
    }
  });

  document.addEventListener('focusout', function (e) {
    var picker = e.target.closest('.entity-picker');
    if (!picker) return;
    setTimeout(function () {
      if (!picker.contains(document.activeElement)) {
        var input = picker.querySelector('.entity-picker__input');
        var dropdown = picker.querySelector('.entity-picker__dropdown');
        if (input && dropdown) pickerClear(input, dropdown);
      }
    }, 150);
  });

  document.addEventListener('htmx:afterSwap', function (e) {
    var dropdown = e.target;
    if (!dropdown || !dropdown.classList.contains('entity-picker__dropdown')) return;
    var picker = dropdown.closest('.entity-picker');
    var input = picker ? picker.querySelector('.entity-picker__input') : null;
    if (!input) return;
    input.setAttribute('aria-expanded', dropdown.children.length > 0 ? 'true' : 'false');
  });
})();
