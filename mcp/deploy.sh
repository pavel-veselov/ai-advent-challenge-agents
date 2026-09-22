#!/usr/bin/env bash
#
# ============================================================================
#  deploy.sh — развёртывание MCP-сервера «Папкин помощник» (Papkin Helper)
#  на VPS из уже скопированной папки проекта (без git clone).
#
#  Что делает:
#    1) Открывает TCP-порт в файрволе ХОСТА (ufw / firewalld / iptables).
#    2) Собирает и запускает контейнер через docker compose up -d --build.
#    3) Проверяет, что MCP endpoint /mcp отвечает.
#
#  Запуск (на VPS, из папки mcp/, с sudo):
#      sudo ./deploy.sh
#      sudo PORT=8788 ./deploy.sh          # другой внешний порт
#
#  Требования на VPS: docker + плагин docker compose, sudo, curl.
#
#  ВАЖНО: файрвол облачного провайдера (security group / firewall rules)
#  этим скриптом НЕ открывается — это надо сделать в панели провайдера
#  (входящий TCP на тот же порт). Скрипт не может дотянуться до неё.
# ============================================================================

set -euo pipefail

# ---- Конфигурация (переопределяется через переменные окружения) ----
PORT="${PORT:-8787}"            # внешний порт на хосте
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"

log()  { echo "==> $*"; }
warn() { echo "!!> $*"; }

# ---------------------------------------------------------------------------
log "[1/5] Открываю TCP-порт ${PORT} на хосте"
# ---------------------------------------------------------------------------
if command -v ufw >/dev/null 2>&1; then
    ufw allow "${PORT}/tcp" && echo "    ufw: открыт ${PORT}/tcp" || warn "ufw: не удалось (может, уже открыт)"
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port="${PORT}/tcp" \
        && firewall-cmd --reload \
        && echo "    firewalld: открыт ${PORT}/tcp" \
        || warn "firewalld: не удалось"
fi

# Best-effort fallback для iptables (системы без ufw/firewalld).
if command -v iptables >/dev/null 2>&1; then
    iptables -C INPUT -p tcp --dport "${PORT}" -j ACCEPT 2>/dev/null \
        || iptables -I INPUT -p tcp --dport "${PORT}" -j ACCEPT 2>/dev/null \
        || true
    echo "    iptables: правило добавлено (best-effort)"
fi

# ---------------------------------------------------------------------------
log "[2/5] Собираю и запускаю контейнер (docker compose)"
# ---------------------------------------------------------------------------
if [ ! -f "${COMPOSE_FILE}" ]; then
    warn "Не найден ${COMPOSE_FILE} — запускай скрипт из папки mcp/."
    exit 1
fi
docker compose -f "${COMPOSE_FILE}" up -d --build

# ---------------------------------------------------------------------------
log "[3/5] Жду готовности сервера"
# ---------------------------------------------------------------------------
for i in $(seq 1 15); do
    if curl -fsS -o /dev/null "http://127.0.0.1:${PORT}/mcp" 2>/dev/null; then
        break
    fi
    sleep 2
done

# ---------------------------------------------------------------------------
log "[4/5] Проверяю MCP endpoint"
# ---------------------------------------------------------------------------
if curl -fsS -o /dev/null "http://127.0.0.1:${PORT}/mcp" 2>/dev/null; then
    echo "    OK: сервер отвечает на http://127.0.0.1:${PORT}/mcp"
else
    warn "/mcp не ответил. Смотри логи:"
    warn "    docker compose -f ${COMPOSE_FILE} logs -f"
    exit 1
fi

# ---------------------------------------------------------------------------
log "[5/5] Готово"
# ---------------------------------------------------------------------------
IP_HOST=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
echo ""
echo "  MCP-сервер «Папкин помощник» запущен."
echo "  Адрес для MCP-клиента:  http://<IP-VPS>:${PORT}/mcp"
echo "  (локально проверил:      http://127.0.0.1:${PORT}/mcp, хост ${IP_HOST})"
echo ""
echo "  НЕ ЗАБУДЬ: открыть входящий TCP ${PORT} в security group провайдера!"
