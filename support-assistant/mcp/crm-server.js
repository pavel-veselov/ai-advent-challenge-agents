// MCP (stdio) CRM-сервер для support-assistant.
// JSON-RPC 2.0, newline-delimited JSON на stdin/stdout.
// Данные: data/users.json + data/tickets.json (заглушка CRM).
import { createInterface } from "node:readline";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.join(here, "..", "data");

async function load(name) {
  const raw = await readFile(path.join(DATA_DIR, name), "utf8");
  return JSON.parse(raw);
}

function fmtUser(u) {
  if (!u) return "Пользователь не найден.";
  return [
    `ID: ${u.id}`,
    `Имя: ${u.name}`,
    `Email: ${u.email}`,
    `Тариф: ${u.plan}`,
    `Статус: ${u.status}`,
    u.blockedReason ? `Причина блокировки: ${u.blockedReason}` : "",
    `Регистрация: ${u.createdAt}`,
  ].filter(Boolean).join("\n");
}

function fmtTicket(t, withNotes = true) {
  if (!t) return "Тикет не найден.";
  const lines = [
    `Тикет ${t.id} (${t.status}, приоритет: ${t.priority}, канал: ${t.channel})`,
    `Тема: ${t.subject}`,
    `Создан: ${t.createdAt} · обновлён: ${t.updatedAt}`,
  ];
  if (withNotes && t.notes?.length) {
    lines.push("Заметки:");
    for (const n of t.notes) lines.push(`  - [${n.date}] ${n.author}: ${n.text}`);
  }
  return lines.join("\n");
}

const TOOLS = [
  {
    name: "get_user",
    description: "Пользователь CRM по email или ID (статус аккаунта, тариф, причина блокировки)",
    inputSchema: {
      type: "object",
      properties: {
        email: { type: "string", description: "Email пользователя (если известен)" },
        id: { type: "string", description: "ID пользователя, например U-1002" },
      },
      additionalProperties: false,
    },
  },
  {
    name: "get_ticket",
    description: "Тикет по ID: тема, статус, приоритет, история заметок",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string", description: "ID тикета, например T-1023" } },
      additionalProperties: false,
    },
  },
  {
    name: "search_tickets",
    description: "Поиск тикетов: по userId/email и/или статусу (open/resolved); без фильтров — все открытые",
    inputSchema: {
      type: "object",
      properties: {
        userId: { type: "string", description: "ID пользователя" },
        email: { type: "string", description: "Email пользователя (альтернатива userId)" },
        status: { type: "string", description: "open | resolved" },
      },
      additionalProperties: false,
    },
  },
  {
    name: "create_ticket",
    description: "Создать тикет в CRM (userId, тема, приоритет)",
    inputSchema: {
      type: "object",
      properties: {
        userId: { type: "string" },
        subject: { type: "string" },
        priority: { type: "string", description: "low | normal | high (по умолчанию normal)" },
      },
      required: ["userId", "subject"],
      additionalProperties: false,
    },
  },
];

async function callTool(name, args) {
  args = args || {};
  try {
    if (name === "get_user") {
      const { users } = await load("users.json");
      const u = args.email
        ? users.find((x) => x.email.toLowerCase() === String(args.email).toLowerCase())
        : users.find((x) => x.id === args.id);
      return { text: fmtUser(u) };
    }
    if (name === "get_ticket") {
      const { tickets } = await load("tickets.json");
      return { text: fmtTicket(tickets.find((t) => t.id === args.id)) };
    }
    if (name === "search_tickets") {
      const { tickets } = await load("tickets.json");
      const { users } = await load("users.json");
      let list = tickets;
      if (args.userId) list = list.filter((t) => t.userId === args.userId);
      if (args.email) {
        const u = users.find((x) => x.email.toLowerCase() === String(args.email).toLowerCase());
        list = u ? list.filter((t) => t.userId === u.id) : [];
      }
      if (args.status) list = list.filter((t) => t.status === args.status);
      if (!list.length) return { text: "Тикетов по фильтру не найдено." };
      return { text: list.map((t) => fmtTicket(t, false)).join("\n\n") };
    }
    if (name === "create_ticket") {
      const file = path.join(DATA_DIR, "tickets.json");
      const { tickets } = await load("tickets.json");
      const maxNum = tickets.reduce((m, t) => Math.max(m, Number(t.id.slice(2)) || 0), 1000);
      const ticket = {
        id: `T-${maxNum + 1}`,
        userId: args.userId,
        subject: String(args.subject).slice(0, 200),
        status: "open",
        priority: ["low", "normal", "high"].includes(args.priority) ? args.priority : "normal",
        channel: "assistant",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        notes: [{ author: "assistant", date: new Date().toISOString(), text: "Тикет создан AI-ассистентом поддержки." }],
      };
      tickets.push(ticket);
      const { writeFile } = await import("node:fs/promises");
      await writeFile(file, JSON.stringify({ tickets }, null, 2) + "\n", "utf8");
      return { text: `Тикет создан: ${ticket.id}\n${fmtTicket(ticket, false)}` };
    }
    return { text: `неизвестный инструмент: ${name}`, isError: true };
  } catch (e) {
    return { text: `Ошибка CRM: ${e.message}`, isError: true };
  }
}

const rl = createInterface({ input: process.stdin });

function respond(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

rl.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let req;
  try {
    req = JSON.parse(trimmed);
  } catch {
    return;
  }
  const { id, method, params } = req;

  if (method === "initialize") {
    respond({
      jsonrpc: "2.0",
      id,
      result: {
        protocolVersion: "2024-11-05",
        capabilities: { tools: {} },
        serverInfo: { name: "support-crm-mcp", version: "1.0.0" },
      },
    });
    return;
  }
  if (method === "notifications/initialized" || method === "initialized") return;
  if (method === "ping") {
    respond({ jsonrpc: "2.0", id, result: {} });
    return;
  }
  if (method === "tools/list") {
    respond({ jsonrpc: "2.0", id, result: { tools: TOOLS } });
    return;
  }
  if (method === "tools/call") {
    const { name, arguments: args } = params || {};
    callTool(name, args).then((r) =>
      respond({
        jsonrpc: "2.0",
        id,
        result: { content: [{ type: "text", text: r.text }], isError: Boolean(r.isError) },
      })
    );
    return;
  }
  respond({ jsonrpc: "2.0", id, error: { code: -32601, message: `Метод не найден: ${method}` } });
});
