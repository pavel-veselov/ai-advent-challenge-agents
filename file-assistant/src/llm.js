// GPUStack chat call with SSE streaming (паттерн из support-assistant, день 33).
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

// Один не-стриминговый вызов, который может вернуть tool_calls.
async function chatOnce(messages, tools, signal) {
  const res = await fetch(`${config.baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${config.apiKey}`,
    },
    body: JSON.stringify({
      model: config.model,
      messages,
      tools,
      tool_choice: "auto",
      stream: false,
    }),
    signal,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`GPUStack ${res.status}: ${text.slice(0, 200)}`);
  }
  const data = await res.json();
  return data.choices?.[0]?.message || {};
}

// Цикл вызова инструментов (glm-5.3-flash поддерживает OpenAI-style tools с
// tool_choice:"auto" — проверено в support-assistant, день 33).
// Итерации с инструментами идут не-стримингово; финальный ответ стримится.
// Здесь максимум 6 итераций: файловые задачи требуют цепочек поиск → чтение → запись.
export async function chatWithTools({ messages, tools, executeTool, onDelta, signal, maxIterations = 6 } = {}) {
  const convo = messages.map((m) => ({ ...m }));
  for (let i = 1; i <= maxIterations; i++) {
    if (i === maxIterations) {
      // Последняя итерация — стримим финальный ответ без инструментов.
      return chatComplete(convo, { onDelta, signal });
    }
    const msg = await chatOnce(convo, tools, signal);
    const calls = Array.isArray(msg.tool_calls) ? msg.tool_calls : [];
    if (!calls.length) {
      const text = typeof msg.content === "string" ? msg.content : "";
      if (text) onDelta?.(text);
      return text;
    }
    convo.push({ role: "assistant", content: msg.content || "", tool_calls: calls });
    for (const c of calls) {
      let out;
      try {
        const fnArgs = c.function?.arguments ? JSON.parse(c.function.arguments) : {};
        out = await executeTool(c.function?.name || "", fnArgs);
      } catch (e) {
        out = `Ошибка инструмента: ${e.message}`;
      }
      convo.push({ role: "tool", tool_call_id: c.id, content: String(out) });
    }
  }
  return "";
}
