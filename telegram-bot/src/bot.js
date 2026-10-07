/**
 * Душевный Telegram-бот — чат-собеседник на LLM (grammY, long polling).
 * Провайдеры и модели — как в llm-agent-app:
 * - gpustack — фиксированный каталог, Bearer-ключ;
 * - ollama — живое обнаружение моделей, без ключа;
 * - при смене провайдера модель сбрасывается на первую модель нового провайдера;
 * - RAG: вопросы по инструкции пылесоса (LEGEE D8 LuLu) — поиск по базе знаний,
 *   вкл/выкл командой /kb (по умолчанию вкл, любые ошибки — fail-open в обычный чат).
 */
import { Bot } from "grammy";

import config, { PROVIDERS, SYSTEM_PROMPT } from "./config.js";
import { GPUSTACK_MODELS, listOllamaModels } from "./catalog.js";
import { markdownToTelegramHtml } from "./format.js";
import { chatCompletion } from "./llm.js";
import { kbInfo, retrieve } from "./rag.js";
import { store } from "./store.js";

/** Целевой размер сырого чанка (Telegram держит 4090 символов; HTML добавляет теги). */
const CHUNK_TARGET = 3800;

/** Число ```-маркеров (для отслеживания открытых fenced-блоков). */
function countFences(text) {
  return (text.match(/```/g) ?? []).length;
}

/** Жёсткое деление огромного абзаца: последний \n/пробел перед лимитом, иначе разрез. */
function hardSplit(paragraph, limit = CHUNK_TARGET) {
  const parts = [];
  let rest = paragraph;
  while (rest.length > limit) {
    let cut = rest.lastIndexOf("\n", limit);
    if (cut <= 0) cut = rest.lastIndexOf(" ", limit);
    if (cut <= 0) cut = limit;
    parts.push(rest.slice(0, cut));
    rest = rest.slice(cut).replace(/^[ \n]+/, "");
  }
  if (rest.length > 0) parts.push(rest);
  return parts;
}

/**
 * Делит сырой markdown на чанки по абзацам (пустая строка — граница), ≤ CHUNK_TARGET.
 * Абзац не разрывается; чанк не закрывается при открытом fenced-блоке (нечётное число ```).
 */
function splitIntoChunks(text, target = CHUNK_TARGET) {
  if (text.length <= target) return [text];

  const paragraphs = text
    .split(/\n[ \t]*\n+/)
    .flatMap((p) => (p.length > target ? hardSplit(p, target) : [p]));
  const chunks = [];
  let current = [];
  let currentLen = 0;
  let fenceOpen = false;

  for (const paragraph of paragraphs) {
    if (current.length > 0 && currentLen + paragraph.length + 2 > target && !fenceOpen) {
      chunks.push(current.join("\n\n"));
      current = [];
      currentLen = 0;
    }
    current.push(paragraph);
    currentLen += paragraph.length + 2;
    fenceOpen = (fenceOpen + countFences(paragraph)) % 2 === 1;
  }

  if (current.length > 0) chunks.push(current.join("\n\n"));
  return chunks;
}

/** Отправляет чанк как HTML; ошибка парсинга/400 → fallback на plain text. */
async function sendChunk(api, chatId, rawChunk) {
  const html = markdownToTelegramHtml(rawChunk);
  try {
    await api.sendMessage(chatId, html, { parse_mode: "HTML" });
  } catch (error) {
    const description = String(error?.message ?? error);
    if (/can't parse|\(400:/i.test(description)) {
      await api.sendMessage(chatId, rawChunk);
    } else {
      throw error;
    }
  }
}

/**
 * Отрезает хвостовой список источников, если модель напечатала его сама
 * (страховка от дубля: бот добавит свой детерминированный блок «Источники:»).
 * Срезаем только хвост: строка, начинающаяся с «Источники», до конца ответа.
 */
function stripTrailingSources(text) {
  return text.replace(/\n*\s*Источники[^\n]*\n[\s\S]*$/, "").trimEnd();
}

const GREETING = [
  "Привет! Я душевный собеседник — поболтаем?",
  "",
  "Команды:",
  "/help — справка",
  "/provider — показать или сменить провайдера LLM",
  "/model — показать или сменить модель LLM",
  "/reset — очистить историю этого чата",
  "/kb — RAG по инструкции пылесоса (вкл/выкл)",
].join("\n");

const COMMANDS = [
  { command: "start", description: "Начать общение" },
  { command: "help", description: "Список команд" },
  { command: "provider", description: "Показать/сменить провайдера LLM" },
  { command: "model", description: "Показать/сменить модель LLM" },
  { command: "reset", description: "Очистить историю этого чата" },
  { command: "kb", description: "RAG по инструкции пылесоса (вкл/выкл)" },
];

/** Аргументы команды: всё, что идёт после первого слова. */
function commandArg(text) {
  return (text ?? "").split(/\s+/).slice(1).join(" ").trim();
}

/** Список моделей текущего провайдера (ollama — живой, может бросить ошибку). */
async function modelList(provider) {
  if (provider === "gpustack") return [...GPUSTACK_MODELS];
  return listOllamaModels();
}

/** Первый (дефолтный) идентификатор модели провайдера. */
function firstModelOf(provider, models) {
  if (provider === "gpustack") return GPUSTACK_MODELS[0];
  return models[0] ?? null;
}

function formatModelList(provider, models) {
  const lines = models.map((id, index) => `${index + 1}. ${id}`);
  return [
    `Текущая модель: ${store.getModel()}`,
    `Модели провайдера ${provider}:`,
    ...lines,
    "",
    "Сменить: /model <номер> или /model <id>",
  ].join("\n");
}

const bot = new Bot(config.telegramBotToken);

bot.command("start", (ctx) => ctx.reply(GREETING));
bot.command("help", (ctx) => ctx.reply(GREETING));

bot.command("provider", async (ctx) => {
  const arg = commandArg(ctx.message.text).toLowerCase();
  const current = store.getProvider();

  if (!arg) {
    await ctx.reply(
      [
        `Текущий провайдер: ${current}`,
        `Доступные: ${PROVIDERS.join(", ")}`,
        "",
        "Сменить: /provider gpustack или /provider ollama",
        "(при смене провайдера модель сбрасывается на первую из его каталога)",
      ].join("\n"),
    );
    return;
  }

  if (!PROVIDERS.includes(arg)) {
    await ctx.reply(`Неизвестный провайдер "${arg}". Доступные: ${PROVIDERS.join(", ")}`);
    return;
  }

  if (arg === current) {
    await ctx.reply(`Провайдер ${current} уже выбран, модель: ${store.getModel()}`);
    return;
  }

  if (arg === "ollama") {
    let models;
    try {
      models = await listOllamaModels();
    } catch (error) {
      await ctx.reply(`Не удалось переключиться: ${error.message}`);
      return;
    }
    if (models.length === 0) {
      await ctx.reply("Не удалось переключиться: Ollama доступна, но моделей не обнаружено.");
      return;
    }
    store.setProvider("ollama");
    store.setModel(firstModelOf("ollama", models));
    await ctx.reply(`Провайдер: ollama. Модель сброшена на первую из каталога: ${store.getModel()}`);
    return;
  }

  store.setProvider("gpustack");
  store.setModel(firstModelOf("gpustack"));
  await ctx.reply(`Провайдер: gpustack. Модель сброшена на первую из каталога: ${store.getModel()}`);
});

bot.command("model", async (ctx) => {
  const arg = commandArg(ctx.message.text);
  const provider = store.getProvider();

  let models;
  try {
    models = await modelList(provider);
  } catch (error) {
    await ctx.reply(error.message);
    return;
  }

  if (!arg) {
    await ctx.reply(formatModelList(provider, models));
    return;
  }

  let next = null;
  if (/^\d+$/.test(arg)) {
    const index = Number(arg) - 1;
    if (index >= 0 && index < models.length) next = models[index];
  } else if (models.includes(arg)) {
    next = arg;
  }

  if (!next) {
    await ctx.reply(
      `Модель "${arg}" недоступна у провайдера ${provider}.\nДоступные: ${
        models.join(", ") || "— (список пуст)"
      }`,
    );
    return;
  }

  store.setModel(next);
  await ctx.reply(`Модель установлена: ${next}`);
});

bot.command("reset", (ctx) => {
  store.clearHistory(ctx.chat.id);
  ctx.reply("История этого чата очищена. Начнём заново.");
});

bot.command("kb", async (ctx) => {
  const arg = commandArg(ctx.message.text).toLowerCase();

  // /kb on|off — переключение; /kb без аргументов — статус.
  if (arg !== "on" && arg !== "off") {
    if (arg) {
      await ctx.reply("Не понял аргумент. Используйте /kb on или /kb off.");
      return;
    }
    const enabled = store.getRag(ctx.chat.id);
    const info = kbInfo();
    const status = enabled
      ? info
        ? `RAG: вкл. База «${info.name}» (id ${info.kbId}), чанков: ${info.chunkCount}.`
        : "RAG: вкл. База не загружена (нет снапшота data/rag-vacuum.json)."
      : "RAG: выкл.";
    await ctx.reply(`${status}\nПереключить: /kb on или /kb off`);
    return;
  }

  store.setRag(ctx.chat.id, arg === "on");
  await ctx.reply(
    arg === "on"
      ? "RAG включён: вопросы по инструкции пылесоса будут отвечаться по базе знаний."
      : "RAG выключен: обычный душевный чат.",
  );
});

/** Обычный текст: user → (опционально RAG) → LLM → assistant. История — последние 10 сообщений. */
bot.on("message:text", async (ctx) => {
  const chatId = ctx.chat.id;

  store.appendMessage(chatId, { role: "user", content: ctx.message.text });

  await ctx.api.sendChatAction(chatId, "typing");

  // RAG: ищем фрагменты в инструкции пылесоса (fail-open — никогда не бросает).
  let ragBlock = null;
  let ragSources = null;
  if (store.getRag(chatId)) {
    const result = await retrieve(ctx.message.text);
    if (result.block) {
      ragBlock = result.block;
      ragSources = result.sources;
      // Поиск по базе занял время — обновляем индикатор «печатает».
      await ctx.api.sendChatAction(chatId, "typing");
    }
  }

  try {
    const messages = [
      { role: "system", content: SYSTEM_PROMPT },
      ...(ragBlock ? [{ role: "system", content: ragBlock }] : []),
      ...store.getHistory(chatId),
    ];
    const reply = await chatCompletion({
      provider: store.getProvider(),
      model: store.getModel(),
      messages,
    });
    // В историю — чистый ответ; блок источников нужен только для показа.
    store.appendMessage(chatId, { role: "assistant", content: reply });
    const text = ragSources
      ? `${stripTrailingSources(reply)}\n\nИсточники:\n${ragSources
          .map((s) => `[${s.label}] ${s.source} — ${s.section}`)
          .join("\n")}`
      : reply;
    for (const chunk of splitIntoChunks(text)) {
      await sendChunk(ctx.api, chatId, chunk);
    }
  } catch (error) {
    // Ошибка LLM: откатываем user-сообщение — в истории ничего не остаётся.
    store.popLastMessage(chatId);
    console.error("Ошибка LLM:", error.message);
    await ctx.reply(`Что-то пошло не так, не получилось ответить.\nПричина: ${error.message}`);
  }
});

bot.catch((error) => {
  console.error("Ошибка обработки обновления:", error);
});

async function main() {
  await bot.api.setMyCommands(COMMANDS);
  console.log(`Провайдер: ${store.getProvider()}; модель: ${store.getModel()}`);
  bot.start({
    onStart: (me) => console.log(`Bot started as @${me.username}`),
  });
}

main().catch((error) => {
  console.error("Не удалось запустить бота:", error.message);
  process.exit(1);
});
