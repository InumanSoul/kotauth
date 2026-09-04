/**
 * callback-template.js — keep the callback URL on the add-provider form in step with the
 * provider key being typed.
 *
 * The callback URL contains the provider key, so on the add form there is nothing to show until
 * a key exists. Without this the operator has to register a placeholder redirect URI at the
 * issuer, save here, then go back and correct it — the URL is only knowable after the thing it
 * is needed for. Progressive enhancement: with no JS the template renders with its placeholder,
 * which is still readable.
 */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', () => {
    const keyInput = document.querySelector('[data-callback-key-input]');
    const target = document.querySelector('[data-callback-template]');
    if (!keyInput || !target) return;

    const template = target.getAttribute('data-callback-template') || '';
    const placeholder = 'provider-key';
    const copyButton = target.parentElement
      ? target.parentElement.querySelector('[data-copy]')
      : null;

    const sync = () => {
      const key = keyInput.value.trim() || placeholder;
      const url = template.replace(placeholder, key);
      target.textContent = url;
      if (copyButton) copyButton.setAttribute('data-copy', url);
    };

    sync();
    keyInput.addEventListener('input', sync);
  });
})();
