#!/usr/bin/env node
/**
 * generate-sri.js — generates js-integrity.properties after esbuild runs.
 *
 * Reads each JS bundle, computes SHA-256, writes a properties file that
 * the Kotlin app loads at startup to inject integrity attributes on <script> tags.
 *
 * No npm dependencies — uses Node.js built-in crypto module.
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../../');

const bundles = {
  'js.admin.integrity': 'src/main/resources/static/js/kotauth-admin.js',
  'js.auth.integrity': 'src/main/resources/static/js/kotauth-auth.js',
  'js.portal.integrity': 'src/main/resources/static/js/kotauth-portal.js',
  'js.branding.integrity': 'src/main/resources/static/js/branding.min.js',
};

const lines = Object.entries(bundles).map(([key, filePath]) => {
  const abs = path.resolve(root, filePath);
  const content = fs.readFileSync(abs);
  const hash = crypto.createHash('sha256').update(content).digest('base64');
  return `${key}=sha256-${hash}`;
});

const outPath = path.resolve(root, 'src/main/resources/js-integrity.properties');
fs.writeFileSync(outPath, lines.join('\n') + '\n');
console.log('SRI hashes written to', outPath);
