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
  const updateCount = (gridEl) => {
    const countEl = gridEl.parentElement?.querySelector('.chip-grid__count');
    if (!countEl) return;
    const boxes = gridEl.querySelectorAll('input[type="checkbox"]');
    const checked = gridEl.querySelectorAll('input[type="checkbox"]:checked').length;
    countEl.textContent = `${checked} / ${boxes.length} selected`;
  };

  const setAll = (gridId, state) => {
    const grid = document.getElementById(gridId);
    if (!grid) return;
    grid.querySelectorAll('input[type="checkbox"]').forEach((cb) => {
      cb.checked = state;
    });
    updateCount(grid);
  };

  // ── chip-grid: All / None buttons ──────────────────────────
  document.addEventListener('click', (e) => {
    const allBtn = e.target.closest('[data-chips-all]');
    if (allBtn) {
      e.preventDefault();
      setAll(allBtn.getAttribute('data-chips-all'), true);
      return;
    }

    const noneBtn = e.target.closest('[data-chips-none]');
    if (noneBtn) {
      e.preventDefault();
      setAll(noneBtn.getAttribute('data-chips-none'), false);
    }
  });

  // ── chip-grid: live count on checkbox change ───────────────
  document.addEventListener('change', (e) => {
    if (!e.target.matches('.chip-grid input[type="checkbox"]')) return;
    const grid = e.target.closest('.chip-grid');
    if (grid) updateCount(grid);
  });

  // ── copy to clipboard ──────────────────────────────────────
  const CHECK_SVG = '<svg width="13" height="13" viewBox="0 0 15 15" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M3 8l3 3 6-7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-copy]');
    if (!btn) return;
    e.preventDefault();
    const text = btn.getAttribute('data-copy');
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        const origHtml = btn.innerHTML;
        btn.innerHTML = CHECK_SVG;
        btn.setAttribute('data-copied', '');
        setTimeout(() => {
          btn.innerHTML = origHtml;
          btn.removeAttribute('data-copied');
        }, 1500);
      });
    }
  });

  // ── scope toggle (show/hide field by select value) ────────
  document.addEventListener('change', (e) => {
    const sel = e.target.closest('[data-scope-toggle]');
    if (!sel) return;
    const target = document.getElementById(sel.getAttribute('data-scope-toggle'));
    if (!target) return;
    target.style.display = sel.value === 'client' ? '' : 'none';
  });

  // ── auto-submit on change ─────────────────────────────────
  document.addEventListener('change', (e) => {
    const el = e.target.closest('[data-autosubmit]');
    if (!el || !el.form) return;
    el.form.submit();
  });

  // ── save button feedback ──────────────────────────────────
  document.addEventListener('submit', (e) => {
    const form = e.target;
    if (!form || form.tagName !== 'FORM') return;
    const btn = form.querySelector('button[type="submit"].btn--primary');
    if (!btn) return;
    btn.disabled = true;
    btn.setAttribute('data-original-text', btn.textContent);
    btn.textContent = 'Saving\u2026';
  });

  // ── invite/password radio toggle on create-user form ────
  document.addEventListener('change', (e) => {
    const radio = e.target.closest('[data-setup-toggle]');
    if (!radio) return;
    const pwField = document.getElementById('passwordField');
    if (!pwField) return;
    const pwInput = pwField.querySelector('input[name="password"]');
    if (radio.value === 'invite') {
      pwField.style.display = 'none';
      if (pwInput) pwInput.removeAttribute('required');
    } else {
      pwField.style.display = '';
      if (pwInput) pwInput.setAttribute('required', 'true');
    }
  });

  // ── entity-picker: keyboard navigation ──────────────────
  const pickerSetFocused = (input, items, target) => {
    items.forEach((item) => { item.classList.remove('entity-picker__item--focused'); });
    if (!target) return;
    target.classList.add('entity-picker__item--focused');
    input.setAttribute('aria-activedescendant', target.id || '');
    target.scrollIntoView({ block: 'nearest' });
  };

  const pickerClear = (input, dropdown) => {
    input.value = '';
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-activedescendant', '');
    dropdown.innerHTML = '';
  };

  document.addEventListener('keydown', (e) => {
    const input = e.target.closest('.entity-picker__input');
    if (!input) return;
    const dropdown = input.closest('.entity-picker')?.querySelector('.entity-picker__dropdown');
    if (!dropdown) return;
    const items = Array.from(dropdown.querySelectorAll('.entity-picker__item'));
    if (!items.length) return;
    const focused = dropdown.querySelector('.entity-picker__item--focused');
    const idx = focused ? items.indexOf(focused) : -1;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      pickerSetFocused(input, items, items[idx + 1] || items[0]);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      pickerSetFocused(input, items, items[idx - 1] || items[items.length - 1]);
    } else if (e.key === 'Enter' && focused) {
      e.preventDefault();
      const btn = focused.querySelector('[data-entity-picker-item]');
      if (btn) btn.click();
    } else if (e.key === 'Escape') {
      pickerClear(input, dropdown);
    }
  });

  document.addEventListener('focusout', (e) => {
    const picker = e.target.closest('.entity-picker');
    if (!picker) return;
    setTimeout(() => {
      if (!picker.contains(document.activeElement)) {
        const input = picker.querySelector('.entity-picker__input');
        const dropdown = picker.querySelector('.entity-picker__dropdown');
        if (input && dropdown) pickerClear(input, dropdown);
      }
    }, 150);
  });

  document.addEventListener('htmx:afterSwap', (e) => {
    const dropdown = e.target;
    if (!dropdown?.classList.contains('entity-picker__dropdown')) return;
    const input = dropdown.closest('.entity-picker')?.querySelector('.entity-picker__input');
    if (!input) return;
    input.setAttribute('aria-expanded', dropdown.children.length > 0 ? 'true' : 'false');
  });
})();
