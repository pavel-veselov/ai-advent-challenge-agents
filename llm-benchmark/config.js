// Zero-dependency configuration for the LLM benchmark.
// Credentials come from the environment (process.env) or an optional local .env —
// never hardcoded.

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { TASKS } from './tasks/tasks.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Minimal .env loader (KEY=VALUE lines). Existing non-empty process.env values win.
 * A missing .env is fine — real values may be exported by the caller.
 */
export function loadEnvFile(envPath = path.join(__dirname, '.env')) {
  if (!existsSync(envPath)) return false;
  const raw = readFileSync(envPath, 'utf8');
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
    if (!m) continue;
    let value = m[2].trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    const key = m[1];
    const current = process.env[key];
    if (current === undefined || current === '') process.env[key] = value;
  }
  return true;
}

/** Judge model — runs on GPUStack, independent of the tested models. */
export const JUDGE_MODEL = 'gpt-4o';

/** Container name used by the resource sampler (local ollama). */
export const OLLAMA_CONTAINER = 'ollama';

/** Resolve provider credentials/base URLs from the environment. */
export function getProviders() {
  const gpustackBaseUrl = process.env.LLM_BASE_URL;
  const gpustackApiKey = process.env.LLM_API_KEY;
  const ollamaBaseUrl = process.env.OLLAMA_BASE_URL || 'http://localhost:11434';

  if (!gpustackBaseUrl || !gpustackApiKey) {
    throw new Error(
      'Missing GPUStack credentials: set LLM_BASE_URL and LLM_API_KEY ' +
        '(environment or llm-benchmark/.env — see .env.example).',
    );
  }

  return {
    gpustack: { type: 'gpustack', baseUrl: gpustackBaseUrl, apiKey: gpustackApiKey },
    ollama: { type: 'ollama', baseUrl: ollamaBaseUrl, apiKey: null },
  };
}

function slug(model) {
  return String(model).replace(/[^a-zA-Z0-9._-]+/g, '_');
}

/**
 * The 6-configuration matrix: 3 models × 2 temperatures.
 * Each config carries resolved endpoint credentials for its provider.
 */
export function getConfigs() {
  const providers = getProviders();
  const matrix = [
    { provider: 'gpustack', model: 'glm-5.3-flash', temperature: 0.1 },
    { provider: 'gpustack', model: 'glm-5.3-flash', temperature: 1.0 },
    { provider: 'ollama', model: 'qwen2.5-coder:3b-instruct-q8_0', temperature: 0.1 },
    { provider: 'ollama', model: 'qwen2.5-coder:3b-instruct-q8_0', temperature: 1.0 },
    { provider: 'ollama', model: 'qwen2.5-coder:3b', temperature: 0.1 },
    { provider: 'ollama', model: 'qwen2.5-coder:3b', temperature: 1.0 },
  ];

  return matrix.map(({ provider, model, temperature }) => {
    const p = providers[provider];
    return {
      id: `${provider}/${slug(model)}@t${temperature}`,
      providerType: provider,
      model,
      temperature,
      baseUrl: p.baseUrl,
      apiKey: p.apiKey,
      container: provider === 'ollama' ? OLLAMA_CONTAINER : null,
    };
  });
}

export { TASKS };
