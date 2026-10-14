// GPUStack chat call with SSE streaming.
import { config } from "./config.js";

export async function chatComplete(messages, { onDelta, signal } = {}) {
  const res = await fetch(`${config.baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${config.apiKey}`,
    },
    body: JSON.stringify({
      model: config.model,
      messages,
      stream: true,
      stream_options: { include_usage: true },
    }),
    signal,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`GPUStack ${res.status}: ${text.slice(0, 200)}`);
  }

  let full = "";
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n")) !== -1) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line || !line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (data === "[DONE]") continue;
      let obj;
      try {
        obj = JSON.parse(data);
      } catch {
        continue;
      }
      const delta = obj.choices?.[0]?.delta?.content;
      if (delta) {
        full += delta;
        if (onDelta) onDelta(delta);
      }
    }
  }
  return full;
}
