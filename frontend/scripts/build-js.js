#!/usr/bin/env node
/**
 * build-js.js — esbuild driver for all JS bundles.
 *
 * Concatenates source files and minifies via esbuild's stdin API.
 * Files are IIFEs — no module resolution needed.
 */
const esbuild = require('esbuild');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../../');
const out = (file) => path.join(root, 'src/main/resources/static/js', file);
const src = (...parts) => path.join(root, 'frontend/js', ...parts);

const bundles = [
  {
    name: 'kotauth-admin.min.js',
    files: [
      src('vendor/htmx.min.js'),
      src('shared/htmx-config.js'),
      src('shared/confirm-dialog.js'),
      src('shared/password-validation.js'),
      src('admin/settings.js'),
    ],
  },
  {
    name: 'kotauth-auth.min.js',
    files: [
      src('auth/auth.js'),
      src('shared/confirm-dialog.js'),
      src('shared/password-validation.js'),
    ],
  },
  {
    name: 'kotauth-portal.min.js',
    files: [
      src('vendor/qrcode.min.js'),
      src('shared/confirm-dialog.js'),
      src('shared/password-validation.js'),
      src('portal/mfa.js'),
    ],
  },
  {
    name: 'branding.min.js',
    files: [
      src('branding/branding.js'),
    ],
  },
];

let failed = false;
for (const bundle of bundles) {
  try {
    // Concatenate all source files
    const contents = bundle.files.map((f) => fs.readFileSync(f, 'utf8')).join('\n');

    const result = esbuild.buildSync({
      stdin: { contents, loader: 'js' },
      minify: true,
      platform: 'browser',
      outfile: out(bundle.name),
      write: true,
    });

    if (result.errors.length > 0) {
      console.error('Errors in', bundle.name, result.errors);
      failed = true;
    } else {
      const size = fs.statSync(out(bundle.name)).size;
      console.log('Built', bundle.name, `(${(size / 1024).toFixed(1)} KB)`);
    }
  } catch (e) {
    console.error('Failed to build', bundle.name, e.message);
    failed = true;
  }
}
if (failed) process.exit(1);
