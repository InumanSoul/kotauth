/**
 * Client-side password policy validation.
 *
 * Reads per-tenant policy rules from data-pw-* attributes on the password input,
 * renders an inline checklist below the field, and updates it on every keystroke.
 *
 * States: unmet (gray) → met (green). Red only after blur or submit attempt.
 * Progressive enhancement only — server-side validation remains authoritative.
 */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', () => {
    for (const passwordInput of document.querySelectorAll('input[data-pw-min-length]')) {
      initValidator(passwordInput);
    }
  });

  function initValidator(passwordInput) {
    const minLength = parseInt(passwordInput.getAttribute('data-pw-min-length') || '8', 10);
    const requireUpper   = passwordInput.getAttribute('data-pw-require-upper')   === 'true';
    const requireNumber  = passwordInput.getAttribute('data-pw-require-number')  === 'true';
    const requireSpecial = passwordInput.getAttribute('data-pw-require-special') === 'true';

    // Build rules array — only include rules this tenant enforces
    const rules = [
      { key: 'length', label: `At least ${minLength} characters`, test: (value) => value.length >= minLength },
    ];
    if (requireUpper)   rules.push({ key: 'upper',   label: '1 uppercase letter',   test: (value) => /[A-Z]/.test(value) });
    if (requireNumber)  rules.push({ key: 'number',  label: '1 number',             test: (value) => /[0-9]/.test(value) });
    if (requireSpecial) rules.push({ key: 'special', label: '1 special character',  test: (value) => /[^A-Za-z0-9]/.test(value) });

    if (rules.length === 0) return;

    // Create checklist element
    const requirementsList = document.createElement('ul');
    requirementsList.className = 'pw-requirements';
    requirementsList.setAttribute('aria-label', 'Password requirements');
    requirementsList.setAttribute('aria-live', 'polite');
    requirementsList.setAttribute('aria-atomic', 'false');
    requirementsList.style.display = 'none';

    const ruleItems = rules.map((rule) => {
      const listItem = document.createElement('li');
      listItem.className = 'pw-req';
      listItem.setAttribute('data-rule', rule.key);
      listItem.innerHTML = '<span class="pw-req__icon">&#x2022;</span> <span>' + rule.label + '</span>';
      listItem.setAttribute('aria-label', rule.label + ': not met');
      requirementsList.appendChild(listItem);
      return { element: listItem, rule };
    });

    // Insert after the field's parent container
    const fieldWrapper = passwordInput.closest('.field') || passwordInput.closest('.edit-row') || passwordInput.parentElement;
    fieldWrapper.parentElement.insertBefore(requirementsList, fieldWrapper.nextSibling);

    let isListVisible = false;
    let hasBlurred    = false;

    // Confirm-match state — populated below if a confirm field exists
    let matchErrorElement = null;
    let confirmInput      = null;
    let matchErrorShown   = false;

    passwordInput.addEventListener('input', () => {
      const currentValue = passwordInput.value;

      // Show on first keystroke
      if (!isListVisible && currentValue.length > 0) {
        requirementsList.style.display = '';
        isListVisible = true;
      }

      // Hide if cleared
      if (currentValue.length === 0) {
        requirementsList.style.display = 'none';
        isListVisible = false;
        hasBlurred    = false;
        return;
      }

      for (const { element: ruleElement, rule } of ruleItems) {
        const passed = rule.test(currentValue);
        if (passed) {
          ruleElement.className = 'pw-req pw-req--met';
          ruleElement.querySelector('.pw-req__icon').innerHTML = '&#x2713;';
          ruleElement.setAttribute('aria-label', rule.label + ': met');
        } else {
          ruleElement.className = hasBlurred ? 'pw-req pw-req--failed' : 'pw-req';
          ruleElement.querySelector('.pw-req__icon').innerHTML = '&#x2022;';
          ruleElement.setAttribute('aria-label', rule.label + ': not met');
        }
      }

      // Sync confirm-match error when the primary field is edited
      if (matchErrorShown && confirmInput && confirmInput.value.length > 0) {
        matchErrorElement.style.display = confirmInput.value === currentValue ? 'none' : '';
      }
    });

    passwordInput.addEventListener('blur', () => {
      if (passwordInput.value.length > 0) {
        hasBlurred = true;
        passwordInput.dispatchEvent(new Event('input'));
      }
    });

    // Confirm password match check
    const form = passwordInput.closest('form');
    if (!form) return;

    confirmInput = form.querySelector('input[name="confirmPassword"], input[name="confirm_password"]');
    if (!confirmInput) return;

    matchErrorElement = document.createElement('div');
    matchErrorElement.className = 'pw-match-error';
    matchErrorElement.setAttribute('aria-live', 'polite');
    matchErrorElement.setAttribute('role', 'alert');
    matchErrorElement.style.display = 'none';
    matchErrorElement.textContent = 'Passwords do not match';

    const confirmWrapper = confirmInput.closest('.field') || confirmInput.closest('.edit-row') || confirmInput.parentElement;
    confirmWrapper.parentElement.insertBefore(matchErrorElement, confirmWrapper.nextSibling);

    confirmInput.addEventListener('blur', () => {
      if (confirmInput.value.length > 0 && confirmInput.value !== passwordInput.value) {
        matchErrorElement.style.display = '';
        matchErrorShown = true;
      } else {
        matchErrorElement.style.display = 'none';
      }
    });

    confirmInput.addEventListener('input', () => {
      if (matchErrorShown) {
        matchErrorElement.style.display = confirmInput.value === passwordInput.value ? 'none' : '';
      }
    });
  }
})();
