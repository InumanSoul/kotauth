/**
 * htmx response handling configuration.
 *
 * Allows 422 (Unprocessable Entity) responses to trigger swaps so
 * server-rendered validation errors can replace form sections in-place.
 */
document.addEventListener('DOMContentLoaded', function () {
  htmx.config.responseHandling = [
    { code: '204', swap: false },
    { code: '[23]..', swap: true },
    { code: '422', swap: true },
    { code: '[45]..', swap: false, error: true }
  ];
});
