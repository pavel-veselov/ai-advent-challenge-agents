#!/usr/bin/env bash
#
# ============================================================================
#  deploy_mcp.sh — развёртывание ДВУХ MCP-серверов на VPS из уже скопированной
#  папки проекта (без git clone):
#    • «Папкин помощник» (Papkin Helper) — папка mcp/,  порт хоста 8787;
#    • MCP-2 («Город пользователя»)      — папка mcp2/, порт хоста 8790.
#
#  Что делает:
#    1) Открывает оба TCP-порта в файрволе ХОСТА (ufw / firewalld / iptables).
#    2) Собирает и запускает оба контейнера через docker compose up -d --build.
#    3) Проверяет, что оба MCP endpoint /mcp отвечают (JSON-RPC initialize).
#
#  Запуск (в корне репозитория, с sudo):
#      sudo ./deploy_mcp.sh
#      sudo PORT_MCP=8788 ./deploy_mcp.sh          # другой порт papkin-helper
#      sudo PORT_MCP2=8791 ./deploy_mcp.sh         # другой порт MCP-2
#
#  Настраиваемые порты (env): PORT_MCP (default 8787), PORT_MCP2 (default 8790)
#
#  Требования на VPS: docker + плагин docker compose, sudo, curl.
#
#  ВАЖНО: файрвол облачного провайдера (security group / firewall rules)
#  этим скриптом НЕ открывается — это надо сделать в панели провайдера
#  (входящий TCP на ОБА порта: ${PORT_MCP} и ${PORT_MCP2}). Скрипт не может
#  дотянуться до неё.
# ============================================================================

set -euo pipefail

# ---- Конфигурация (переопределяется через переменные окружения) ----
PORT_MCP="${PORT_MCP:-8787}"     # внешний порт на хосте для papkin-helper (mcp/)
PORT_MCP2="${PORT_MCP2:-8790}"   # внешний порт на хосте для MCP-2 (mcp2/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # корень репозитория
COMPOSE_MCP="${SCRIPT_DIR}/mcp/docker-compose.yml"
COMPOSE_MCP2="${SCRIPT_DIR}/mcp2/docker-compose.yml"

log()  { echo "==> $*"; }
warn() { echo "!!> $*"; }

# Открыть TCP-порт на хосте (ufw / firewalld / iptables, best-effort).
open_port() {
    local port="$1"

    if command -v ufw >/dev/null 2>&1; then
        ufw allow "${port}/tcp" && echo "    ufw: открыт ${port}/tcp" || warn "ufw: не удалось открыть ${port}/tcp (может, уже открыт)"
    elif command -v firewall-cmd >/dev/null 2>&1; then
        firewall-cmd --permanent --add-port="${port}/tcp" \
            && firewall-cmd --reload \
            && echo "    firewalld: открыт ${port}/tcp" \
            || warn "firewalld: не удалось открыть ${port}/tcp"
    fi

    # Best-effort fallback для iptables (системы без ufw/firewalld).
    if command -v iptables >/dev/null 2>&1; then
        iptables -C INPUT -p tcp --dport "${port}" -j ACCEPT 2>/dev/null \
            || iptables -I INPUT -p tcp --dport "${port}" -j ACCEPT 2>/dev/null \
            || true
        echo "    iptables: правило для ${port}/tcp добавлено (best-effort)"
    fi
}

# Проба живости MCP: POST initialize.
# ВАЖНО: GET /mcp на живом Streamable HTTP сервере отвечает 405/4xx,
# поэтому проверять надо настоящим JSON-RPC вызовом initialize.
probe_mcp() {
    local port="$1"
    curl -fsS -o /dev/null -X POST "http://127.0.0.1:${port}/mcp" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"deploy-probe","version":"0.0.1"}}}' 2>/dev/null
}

# Собрать и запустить один compose-проект.
compose_up() {
    local compose_file="$1"
    docker compose -f "${compose_file}" up -d --build
}

# Цикл ожидания готовности одного сервера (до ~30 c).
wait_ready() {
    local port="$1"
    local i
    for i in $(seq 1 15); do
        if probe_mcp "${port}"; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# ---------------------------------------------------------------------------
log "[1/6] Открываю TCP-порты ${PORT_MCP} и ${PORT_MCP2} на хосте"
# ---------------------------------------------------------------------------
open_port "${PORT_MCP}"
open_port "${PORT_MCP2}"

# ---------------------------------------------------------------------------
log "[2/6] Собираю и запускаю papkin-helper (mcp/, порт ${PORT_MCP})"
# ---------------------------------------------------------------------------
if [ ! -f "${COMPOSE_MCP}" ]; then
    warn "Не найден ${COMPOSE_MCP} — запускай скрипт из корня репозитория."
    exit 1
fi
compose_up "${COMPOSE_MCP}"

# ---------------------------------------------------------------------------
log "[3/6] Собираю и запускаю MCP-2 (mcp2/, порт ${PORT_MCP2})"
# ---------------------------------------------------------------------------
if [ ! -f "${COMPOSE_MCP2}" ]; then
    warn "Не найден ${COMPOSE_MCP2} — запускай скрипт из корня репозитория."
    exit 1
fi
compose_up "${COMPOSE_MCP2}"

# ---------------------------------------------------------------------------
log "[4/6] Жду готовности серверов"
# ---------------------------------------------------------------------------
wait_ready "${PORT_MCP}"  || true
wait_ready "${PORT_MCP2}" || true

# ---------------------------------------------------------------------------
log "[5/6] Проверяю MCP endpoints"
# ---------------------------------------------------------------------------
FAIL=0

if probe_mcp "${PORT_MCP}"; then
    echo "    OK: papkin-helper initialize отвечает на http://127.0.0.1:${PORT_MCP}/mcp"
else
    warn "papkin-helper: /mcp не ответил. Смотри логи:"
    warn "    docker compose -f ${COMPOSE_MCP} logs -f"
    FAIL=1
fi

if probe_mcp "${PORT_MCP2}"; then
    echo "    OK: MCP-2 initialize отвечает на http://127.0.0.1:${PORT_MCP2}/mcp"
else
    warn "MCP-2: /mcp не ответил. Смотри логи:"
    warn "    docker compose -f ${COMPOSE_MCP2} logs -f"
    FAIL=1
fi

if [ "${FAIL}" -ne 0 ]; then
    warn "Один из серверов не поднялся — выход."
    exit 1
fi

# ---------------------------------------------------------------------------
log "[6/6] Готово"
# ---------------------------------------------------------------------------
IP_HOST=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
echo ""
echo "  MCP-серверы запущены:"
echo "    • «Папкин помощник»:  http://<IP-VPS>:${PORT_MCP}/mcp"
echo "    • MCP-2:              http://<IP-VPS>:${PORT_MCP2}/mcp"
echo "  (локально проверил:      http://127.0.0.1:${PORT_MCP}/mcp и http://127.0.0.1:${PORT_MCP2}/mcp, хост ${IP_HOST})"
echo ""
echo "  НЕ ЗАБУДЬ: открыть входящий TCP ${PORT_MCP} И ${PORT_MCP2} в security group провайдера!"
