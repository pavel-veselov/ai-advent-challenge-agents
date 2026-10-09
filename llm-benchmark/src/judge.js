// Quality scoring: LLM-as-judge (gpt-4o on GPUStack) + deterministic mustContain checks.

import { chatCompletion } from './provider.js';

const JUDGE_SYSTEM =
  'Ты — строгий и объективный судья ответов LLM на задачи по программированию. ' +
  'Оцени ответ модели по шкале 1–10 (10 — отлично, 1 — провал) по критериям: ' +
  'correctness — техническая правильность; clarity — ясность и структура изложения; ' +
  'completeness — полнота относительно вопроса; codeQuality — качество кода ' +
  '(если кода нет — качество рассуждений). ' +
  'overallScore — итоговая интегральная оценка. ' +
  'judgeNotes — краткое обоснование на русском (1–3 предложения). ' +
  'Верни ТОЛЬКО валидный JSON без markdown-обёртки, в точности такого вида: ' +
  '{"correctness": <1-10>, "clarity": <1-10>, "completeness": <1-10>, ' +
  '"codeQuality": <1-10>, "overallScore": <1-10>, "judgeNotes": "<...>"}';

function clampScore(v) {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) return null;
  return Math.min(10, Math.max(1, n));
}

/** Extract the first balanced JSON object from arbitrary model output. */
export function extractJson(text) {
  const cleaned = String(text ?? '')
    .replace(/```json/gi, '```')
    .trim();
  const start = cleaned.indexOf('{');
  if (start === -1) return null;
  let depth = 0;
  for (let i = start; i < cleaned.length; i++) {
    const ch = cleaned[i];
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) {
        try {
          return JSON.parse(cleaned.slice(start, i + 1));
        } catch {
          return null;
        }
      }
    }
  }
  return null;
}

function failedJudgement(message) {
  return {
    correctness: null,
    clarity: null,
    completeness: null,
    codeQuality: null,
    overallScore: null,
    judgeNotes: 'judge failed: ' + message,
    judgeError: message,
  };
}

/**
 * Judge one answer with an LLM. Independent of the tested models.
 * Returns { correctness, clarity, completeness, codeQuality, overallScore, judgeNotes }
 * (plus judgeError on failure — never throws).
 */
export async function judgeAnswer({ baseUrl, apiKey, model, task, answer }) {
  if (!answer || !String(answer).trim()) {
    return failedJudgement('empty answer, nothing to judge');
  }
  const userPrompt =
    `## Задача (${task.category})\n${task.prompt}\n\n` +
    `## Ответ модели\n${answer}\n\n` +
    'Оцени ответ по критериям и верни только JSON.';

  const attempts = 2;
  let lastError = null;

  for (let i = 0; i < attempts; i++) {
    try {
      const res = await chatCompletion({
        baseUrl,
        apiKey,
        model,
        temperature: 0,
        messages: [
          { role: 'system', content: JUDGE_SYSTEM },
          {
            role: 'user',
            content:
              i === 0
                ? userPrompt
                : userPrompt +
                  '\n\nПРЕДЫДУЩАЯ ПОПЫТКА НЕ СПАРСИЛАСЬ. Верни ИСКЛЮЧИТЕЛЬНО валидный JSON-объект без текста вокруг.',
          },
        ],
        stream: false,
        timeoutMs: 180000,
        providerType: 'judge',
      });

      const json = extractJson(res.text);
      if (!json) {
        lastError = 'judge returned unparseable output';
        continue;
      }
      const correctness = clampScore(json.correctness);
      const clarity = clampScore(json.clarity);
      const completeness = clampScore(json.completeness);
      const codeQuality = clampScore(json.codeQuality);
      const overallScore = clampScore(json.overallScore);
      if (
        correctness === null ||
        clarity === null ||
        completeness === null ||
        codeQuality === null ||
        overallScore === null
      ) {
        lastError = 'judge JSON missing required score fields';
        continue;
      }
      return {
        correctness,
        clarity,
        completeness,
        codeQuality,
        overallScore,
        judgeNotes: typeof json.judgeNotes === 'string' ? json.judgeNotes : '',
      };
    } catch (err) {
      lastError = err.message;
    }
  }

  return failedJudgement(lastError || 'judge failed');
}

/**
 * Deterministic check: every token in mustContain must appear in the answer
 * (case-insensitive substring). Empty/absent list -> deterministicPass: null.
 */
export function deterministicCheck(answer, mustContain) {
  if (!Array.isArray(mustContain) || mustContain.length === 0) {
    return { deterministicPass: null, tokens: [] };
  }
  const lower = String(answer ?? '').toLowerCase();
  const tokens = mustContain.map((t) => ({
    token: String(t),
    found: lower.includes(String(t).toLowerCase()),
  }));
  return { deterministicPass: tokens.every((t) => t.found), tokens };
}
