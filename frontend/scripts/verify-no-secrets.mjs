#!/usr/bin/env node
// Fails if the built frontend contains the provider key or a key-shaped string (Constitution II).
// Run after `npm run build`. The real key is never in this repository, so a clean build passes.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const DIST = new URL('../dist/', import.meta.url).pathname;
const FORBIDDEN = [
  { label: 'variable name GOOGLE_API_KEY', pattern: /GOOGLE_API_KEY/ },
  // Google API keys start with "AIza" followed by 35 URL-safe characters.
  { label: 'Google API key shape', pattern: /AIza[0-9A-Za-z_-]{35}/ },
];
// If the real key is provided in the environment, it must not appear in the bundle either.
const liveKey = process.env.GOOGLE_API_KEY;
// Constitution VI: session credentials must never be kept in browser storage (specs/002-user-auth, FR-013).
const SRC = new URL('../src/', import.meta.url).pathname;
const STORAGE_APIS = [
  { label: 'localStorage', pattern: /\blocalStorage\b/ },
  { label: 'sessionStorage', pattern: /\bsessionStorage\b/ },
];

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      yield* files(path);
    } else {
      yield path;
    }
  }
}

let statSafe;
try {
  statSafe = statSync(DIST);
} catch {
  console.error(`verify-no-secrets: ${DIST} not found. Run "npm run build" first.`);
  process.exit(2);
}
if (!statSafe.isDirectory()) {
  console.error(`verify-no-secrets: ${DIST} is not a directory.`);
  process.exit(2);
}

const findings = [];
for (const file of files(DIST)) {
  const text = readFileSync(file, 'utf8');
  for (const { label, pattern } of FORBIDDEN) {
    if (pattern.test(text)) {
      findings.push(`${file}: ${label}`);
    }
  }
  if (liveKey && liveKey.length > 0 && text.includes(liveKey)) {
    findings.push(`${file}: the configured GOOGLE_API_KEY value`);
  }
}

for (const file of files(SRC)) {
  const text = readFileSync(file, 'utf8');
  for (const { label, pattern } of STORAGE_APIS) {
    if (pattern.test(text)) {
      findings.push(`${file}: uses ${label}, which must not hold session data (Constitution VI)`);
    }
  }
}

if (findings.length > 0) {
  console.error('verify-no-secrets: FAILED');
  for (const finding of findings) {
    console.error(`  ${finding}`);
  }
  process.exit(1);
}
console.log('verify-no-secrets: OK (no provider key or key-shaped string in frontend/dist; no browser storage in frontend/src)');
