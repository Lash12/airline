#!/usr/bin/env node
/**
 * Guard against the deprecated `device-width` media feature creeping back in.
 *
 * `min/max-device-width` keys off the physical screen size, which Firefox for
 * Android reports in physical pixels (~1440 on a Galaxy S24 Ultra). That flips
 * phones into the desktop layout. Always use viewport-relative `min/max-width`.
 *
 * Exits non-zero (and lists offenders) if any stylesheet uses device-width.
 * Run: node e2e/check-no-deprecated-media.mjs
 */
import { readdirSync, readFileSync, statSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'airline-web', 'public', 'stylesheets');
const offenders = [];

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) { walk(full); continue; }
    if (!name.endsWith('.css')) continue;
    const lines = readFileSync(full, 'utf8').split(/\r?\n/);
    lines.forEach((line, i) => {
      if (/(?:min|max)-device-width/.test(line)) {
        offenders.push(`${full.replace(/.*public[\\/]/, 'public/')}:${i + 1}: ${line.trim()}`);
      }
    });
  }
}

walk(root);

if (offenders.length) {
  console.error('FAIL: deprecated device-width media feature found (use min/max-width instead):');
  offenders.forEach(o => console.error('  ' + o));
  process.exit(1);
}
console.log('OK: no deprecated device-width media queries.');
