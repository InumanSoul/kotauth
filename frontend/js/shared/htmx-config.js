/**
 * htmx global configuration.
 *
 * 1. Response handling — allows 422 (Unprocessable Entity) responses to
 *    trigger swaps so server-rendered validation errors replace form
 *    sections in-place.
 *
 * 2. View transitions — enables the View Transitions API for htmx swaps
 *    so same-document partial updates get the same fade animation as
 *    full-page navigations.
 */
document.addEventListener('DOMContentLoaded', function () {
  htmx.config.responseHandling = [
    { code: '204', swap: false },
    { code: '[23]..', swap: true },
    { code: '422', swap: true },
    { code: '[45]..', swap: false, error: true }
  ];
  htmx.config.globalViewTransitions = true;
});
