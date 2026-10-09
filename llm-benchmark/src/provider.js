// OpenAI-compatible chat completion client (built-in fetch, zero deps).
// Supports streaming (SSE) with TTFT measurement and usage capture.

/**
 * Build the chat-completions endpoint from a base URL.
 * Accepts both "https://host" and "https://host/v1" styles.
 */
export function buildEndpoint(baseUrl) {
  const trimmed = String(baseUrl).replace(/\/+$/, '');
  return trimmed.endsWith('/v1')
    ? trimmed + '/chat/completions'
    : trimmed + '/v1/chat/completions';
}

function extractUsage(u) {
  if (!u) return { promptTokens: null, completionTokens: null, totalTokens: null };
  const prompt = typeof u.prompt_tokens === 'number' ? u.prompt_tokens : null;
  const completion = typeof u.completion_tokens === 'number' ? u.completion_tokens : null;
  return {
    promptTokens: prompt,
    completionTokens: completion,
    totalTokens: typeof u.total_tokens === 'number' ? u.total_tokens : (prompt ?? 0) + (completion ?? 0),
  };
}

/**
 * One chat completion against an OpenAI-compatible endpoint.
 *
 * @param {object} opts
 * @param {string} opts.baseUrl      e.g. https://gpustack-host or http://localhost:11434
 * @param {string|null} opts.apiKey  Bearer token (null for local ollama)
 * @param {string} opts.model
 * @param {number} opts.temperature
 * @param {Array<{role:string,content:string}>} opts.messages
 * @param {boolean} [opts.stream=false]  when true, measures TTFT (firstTokenMs)
 * @param {number} [opts.timeoutMs=300000]
 * @param {string} [opts.providerType='unknown']  'gpustack' | 'ollama' — for attribution
 * @returns {Promise<{text, usage, timing, providerType, model, finishReason}>}
 */
export async function chatCompletion(opts) {
  const {
    baseUrl,
    apiKey,
    model,
    temperature,
    messages,
    stream = false,
    timeoutMs = 300000,
    providerType = 'unknown',
  } = opts;

  const endpoint = buildEndpoint(baseUrl);
  const headers = { 'Content-Type': 'application/json' };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const body = { model, temperature, messages, stream };
  if (stream) body.stream_options = { include_usage: true };

  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(new Error(`request timed out after ${timeoutMs}ms`)),
    timeoutMs,
  );
  const started = performance.now();

  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: controller.signal,
    });

    if (!res.ok) {
      const errText = await res.text().catch(() => '');
      throw new Error(`HTTP ${res.status} ${res.statusText}: ${errText.slice(0, 300)}`);
    }

    if (stream) {
      return await consumeStream(res, started, { providerType, model });
    }

    const json = await res.json();
    const totalMs = performance.now() - started;
    return {
      text: json.choices?.[0]?.message?.content ?? '',
      usage: extractUsage(json.usage),
      timing: { firstTokenMs: null, totalMs },
      providerType,
      model,
      finishReason: json.choices?.[0]?.finish_reason ?? null,
    };
  } finally {
    clearTimeout(timer);
  }
}

/** Consume an SSE stream: assemble text, time the first content chunk, capture usage. */
async function consumeStream(res, started, meta) {
  const decoder = new TextDecoder();
  let buffer = '';
  let text = '';
  let firstTokenMs = null;
  let usage = null;
  let finishReason = null;

  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (payload === '[DONE]') continue;
      let evt;
      try {
        evt = JSON.parse(payload);
      } catch {
        continue;
      }
      if (evt.usage) usage = evt.usage;
      const choice = evt.choices?.[0];
      if (choice?.finish_reason) finishReason = choice.finish_reason;
      const delta = choice?.delta?.content;
      if (typeof delta === 'string' && delta.length > 0) {
        if (firstTokenMs === null) firstTokenMs = performance.now() - started;
        text += delta;
      }
    }
  }

  return {
    text,
    usage: extractUsage(usage),
    timing: { firstTokenMs, totalMs: performance.now() - started },
    providerType: meta.providerType,
    model: meta.model,
    finishReason,
  };
}
