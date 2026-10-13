// Minimal MCP (stdio) git server for the dev-assistant.
// Speaks JSON-RPC 2.0 over newline-delimited JSON on stdin/stdout.
import { createInterface } from "node:readline";
import { execFileSync } from "node:child_process";

const TOOLS = [
  {
    name: "git_branch",
    description: "Текущая git-ветка и количество незакоммиченных изменений",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "git_diff",
    description: "Статистика изменений в рабочей копии или между ревизиями",
    inputSchema: {
      type: "object",
      properties: {
        since: { type: "string", description: "Ревизия вида HEAD~1..HEAD (по умолчанию — рабочая копия)" },
        full: { type: "boolean", description: "Показывать полный diff, а не только --stat" },
      },
      additionalProperties: false,
    },
  },
  {
    name: "list_files",
    description: "Список файлов проекта относительно корня",
    inputSchema: {
      type: "object",
      properties: { limit: { type: "number", description: "Максимум файлов (по умолчанию 200)" } },
      additionalProperties: false,
    },
  },
];

function git(...args) {
  return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function callTool(name, args) {
  args = args || {};
  switch (name) {
    case "git_branch": {
      let branch;
      let dirty = "";
      try {
        branch = git("rev-parse", "--abbrev-ref", "HEAD").trim();
      } catch {
        branch = "(не git-репозиторий)";
      }
      try {
        dirty = git("status", "--porcelain");
      } catch {}
      const dirtyCount = dirty ? dirty.split("\n").filter(Boolean).length : 0;
      return { text: `Ветка: ${branch}\nИзменённых файлов: ${dirtyCount}` };
    }
    case "git_diff": {
      try {
        if (args.since) return { text: git("diff", args.since) || "(нет изменений)" };
        if (args.full) return { text: git("diff") || "(нет изменений)" };
        return { text: git("diff", "--stat") || "(нет изменений)" };
      } catch {
        return { text: "не удалось выполнить git diff", isError: true };
      }
    }
    case "list_files": {
      let out = "";
      try {
        out = git("ls-files");
      } catch {}
      const files = out.split("\n").filter(Boolean).slice(0, args.limit || 200);
      return { text: files.join("\n") || "(нет файлов)" };
    }
    default:
      return { text: `неизвестный инструмент: ${name}`, isError: true };
  }
}

const rl = createInterface({ input: process.stdin, output: process.stdout });

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
        serverInfo: { name: "dev-assistant-git-mcp", version: "1.0.0" },
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
    const r = callTool(name, args);
    respond({
      jsonrpc: "2.0",
      id,
      result: {
        content: [{ type: "text", text: r.text }],
        isError: Boolean(r.isError),
      },
    });
    return;
  }
  respond({ jsonrpc: "2.0", id, error: { code: -32601, message: `Метод не найден: ${method}` } });
});
