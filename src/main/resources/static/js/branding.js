/**
 * Branding page — live preview, color sync, preset application.
 *
 * All bindings use data-* attributes (no inline event handlers) so the
 * page works under a strict Content-Security-Policy without 'unsafe-inline'.
 *
 * Expected data attributes (rendered by brandingPageImpl):
 *   data-preset="dark|light|simple"   — theme preset buttons
 *   data-radius="0px|8px|40px"        — border-radius preset buttons
 *   data-radius-input                 — the freeform radius text input
 *   data-color-key="{key}"            — native <input type="color"> elements
 *   data-hex-key="{key}"              — hex text input elements
 *   data-logo-preview                 — logo URL input
 *
 * Expected DOM IDs:
 *   preview-card, preview-body, preview-btn, preview-logo-placeholder,
 *   field-radius, swatch-{key}, native-{key}, hex-{key}
 */
(function () {
  'use strict';

  /* ── preview CSS variable map ── */
  var previewVars = {
    'accent':   '--pm-accent',
    'card-bg':  '--pm-card',
    'input-bg': '--pm-input',
    'border':   '--pm-border',
    'text':     '--pm-text',
    'muted':    '--pm-muted'
  };

  var previewEl   = document.getElementById('preview-card');
  var previewBody = document.getElementById('preview-body');

  /* ── core sync functions ── */

  function applyPreviewVar(key, value) {
    var v = previewVars[key];
    if (v) previewEl.style.setProperty(v, value);
    if (key === 'page-bg') previewBody.style.background = value;
    if (key === 'accent') document.getElementById('preview-btn').style.background = value;
  }

  function syncColor(key, value) {
    document.getElementById('swatch-' + key).style.background = value;
    document.getElementById('hex-' + key).value = value.toUpperCase();
    applyPreviewVar(key, value);
  }

  function syncHex(key, value) {
    var c = value.charAt(0) === '#' ? value : '#' + value;
    if (/^#[0-9A-Fa-f]{6}$/.test(c)) {
      document.getElementById('swatch-' + key).style.background = c;
      document.getElementById('native-' + key).value = c;
      applyPreviewVar(key, c);
    }
  }

  function syncRadius(value) {
    previewEl.style.setProperty('--pm-radius', value);
    previewEl.querySelectorAll('.auth-mock__field, .auth-mock__btn').forEach(function (el) {
      el.style.borderRadius = value;
    });
  }

  function syncPreviewLogo(url) {
    var ph = document.getElementById('preview-logo-placeholder');
    if (url && url.startsWith('http')) {
      var img = document.createElement('img');
      img.src = url;
      img.style.cssText = 'max-width:100%;max-height:100%;object-fit:contain;';
      img.onerror = function () { ph.textContent = 'LOGO'; };
      ph.innerHTML = '';
      ph.appendChild(img);
    } else {
      ph.textContent = 'LOGO';
    }
  }

  /* ── preset definitions ── */
  var presets = {
    dark: {
      accent: '#1FBCFF', 'accent-hover': '#0AAEE8',
      'page-bg': '#0C0C0E', 'card-bg': '#1E1E24',
      'input-bg': '#2A2A32', border: '#2E2E36',
      text: '#EDEDEF', muted: '#6B6B75', radius: '8px'
    },
    light: {
      accent: '#0A6EBD', 'accent-hover': '#085FA3',
      'page-bg': '#F4F5F7', 'card-bg': '#FFFFFF',
      'input-bg': '#F0F1F3', border: '#E0E1E4',
      text: '#111114', muted: '#7A7A85', radius: '8px'
    },
    simple: {
      accent: '#111114', 'accent-hover': '#333336',
      'page-bg': '#FFFFFF', 'card-bg': '#FAFAFA',
      'input-bg': '#F4F4F6', border: '#DDDDE0',
      text: '#111114', muted: '#6B6B75', radius: '0px'
    }
  };

  /* ── event wiring ── */

  // Theme preset buttons: [data-preset]
  document.querySelectorAll('[data-preset]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var p = presets[this.dataset.preset];
      if (!p) return;
      var group = this.closest('.preset-group');
      group.querySelectorAll('.preset-btn').forEach(function (b) {
        b.classList.remove('preset-btn--active');
      });
      this.classList.add('preset-btn--active');
      Object.keys(p).forEach(function (key) {
        if (key === 'radius') {
          document.getElementById('field-radius').value = p[key];
          syncRadius(p[key]);
          return;
        }
        var native = document.getElementById('native-' + key);
        if (native) {
          native.value = p[key];
          syncColor(key, p[key]);
        }
      });
    });
  });

  // Border-radius preset buttons: [data-radius]
  document.querySelectorAll('[data-radius]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var group = this.closest('.preset-group');
      group.querySelectorAll('.preset-btn').forEach(function (b) {
        b.classList.remove('preset-btn--active');
      });
      this.classList.add('preset-btn--active');
      var value = this.dataset.radius;
      document.getElementById('field-radius').value = value;
      syncRadius(value);
    });
  });

  // Freeform radius text input: [data-radius-input]
  var radiusInput = document.querySelector('[data-radius-input]');
  if (radiusInput) {
    radiusInput.addEventListener('input', function () {
      var rg = this.previousElementSibling;
      rg.querySelectorAll('.preset-btn').forEach(function (b) {
        b.classList.remove('preset-btn--active');
      });
      syncRadius(this.value);
    });
  }

  // Native color picker inputs: [data-color-key]
  document.querySelectorAll('[data-color-key]').forEach(function (el) {
    el.addEventListener('input', function () {
      syncColor(this.dataset.colorKey, this.value);
    });
  });

  // Hex text inputs: [data-hex-key]
  document.querySelectorAll('[data-hex-key]').forEach(function (el) {
    el.addEventListener('input', function () {
      syncHex(this.dataset.hexKey, this.value);
    });
  });

  // Logo URL input: [data-logo-preview]
  var logoInput = document.querySelector('[data-logo-preview]');
  if (logoInput) {
    logoInput.addEventListener('input', function () {
      syncPreviewLogo(this.value);
    });
  }
})();
