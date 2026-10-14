// MCP stdio-клиент для CRM-сервера (mcp/crm-server.js).
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(here, "..", "mcp", "crm-server.js");

export class McpClient {
  constructor() {
    this.pending = new Map();
    this.nextId = 1;
    this.ready = false;
  }

  async connect() {
    this.proc = spawn(process.execPath, [SERVER], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    const rl = createInterface({ input: this.proc.stdout });
    rl.on("line", (line) => {
      const t = line.trim();
      if (!t) return;
      let msg;
      try {
        msg = JSON.parse(t);
      } catch {
        return;
      }
      if (msg.id != null && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message || "MCP error"));
        else resolve(msg.result);
      }
    });
    this.proc.stderr.on("data", () => {});
    this.proc.on("exit", () => {
      for (const { reject } of this.pending.values()) reject(new Error("MCP server exited"));
      this.pending.clear();
    });

    await this.request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "support-assistant", version: "1.0.0" },
    });
    this.notify("notifications/initialized", {});
    this.ready = true;
  }

  request(method, params) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.proc.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
    });
  }

  notify(method, params) {
    this.proc.stdin.write(JSON.stringify({ jsonrpc: "2.0", method, params }) + "\n");
  }

  async listTools() {
    const r = await this.request("tools/list", {});
    return r.tools || [];
  }

  async callTool(name, args) {
    const r = await this.request("tools/call", { name, arguments: args || {} });
    const text = (r.content || []).find((c) => c.type === "text")?.text || "";
    return { text, isError: Boolean(r.isError) };
  }

  close() {
    this.proc?.stdin.end();
    this.proc?.kill();
  }
}
