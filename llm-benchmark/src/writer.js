// Result writer: persists the full run as <dir>/run-<timestamp>.json (default results/).

import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** Absolute default results dir: <llm-benchmark>/results (cwd-independent). */
export function defaultResultsDir() {
  return path.join(__dirname, '..', 'results');
}

/** Filesystem-safe timestamp: ISO with ':' -> '-' (e.g. 2026-10-08T12-30-00.000Z). */
export function timestampSlug(d = new Date()) {
  return d.toISOString().replace(/:/g, '-');
}

/**
 * Recursively delete every `apiKey` property from the snapshot so credentials
 * (provider + judge configs) never persist into results/*.json.
 */
function stripApiKeys(value) {
  if (Array.isArray(value)) {
    for (const item of value) stripApiKeys(item);
  } else if (value !== null && typeof value === 'object') {
    for (const key of Object.keys(value)) {
      if (key === 'apiKey') delete value[key];
      else stripApiKeys(value[key]);
    }
  }
  return value;
}

/**
 * Write run results to JSON.
 * @param {object} data  full run document (meta, entries, summary, ...)
 * @param {object} [opts]
 * @param {string} [opts.dir='results']  output directory; relative paths resolve
 *        against llm-benchmark/ so results never land outside the app folder
 * @param {string} [opts.file]  explicit filename override (used by --out); default run-<ts>.json
 * @returns {string} absolute path of the written file
 */
export function writeResults(data, { dir = 'results', file = null } = {}) {
  const baseDir = path.isAbsolute(dir) ? dir : path.join(__dirname, '..', dir);
  const target = file
    ? path.join(baseDir, file)
    : path.join(baseDir, `run-${timestampSlug()}.json`);

  mkdirSync(baseDir, { recursive: true });
  writeFileSync(target, JSON.stringify(stripApiKeys(data), null, 2), 'utf8');
  return path.resolve(target);
}
