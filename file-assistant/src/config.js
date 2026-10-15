// Конфигурация file-assistant из окружения.
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

export const config = {
  baseUrl: process.env.LLM_BASE_URL || "https://gpustack.data.lmru.tech",
  apiKey: process.env.LLM_API_KEY || "",
  model: process.env.LLM_MODEL || "glm-5.3-flash",
  port: Number(process.env.PORT) || 8083,
  // Корень рабочей области: по умолчанию — корень репо (file-assistant/..).
  workspaceRoot: process.env.WORKSPACE_ROOT || path.resolve(here, "..", ".."),
};
