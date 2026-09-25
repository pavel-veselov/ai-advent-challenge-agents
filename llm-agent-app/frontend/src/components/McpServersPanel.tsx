import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { createMcpServer, deleteMcpServer, fetchMcpServers, setMcpServerEnabled } from '../api';
import type { McpServer } from '../types';
import CollapsibleSection from './CollapsibleSection';

/** Сколько миллисекунд висит инлайн-ошибка API, прежде чем погаснуть (идиома InvariantsPanel). */
const ERROR_VISIBLE_MS = 4000;

interface McpServersPanelProps {
  /** true — сервис остановлен/запускается: форма и действия заблокированы. */
  disabled: boolean;
}

/**
 * Панель «MCP-серверы» (Day-16): управление внешними источниками инструментов агента
 * (Model Context Protocol). Глобальная сущность (вне проектов/сессий) — панель сама
 * грузит список на монтировании и перечитывает его после каждой мутации (add/toggle/
 * delete): REST-мутации MCP бэкенд SSE-событием не сопровождает.
 * Форма добавления (name + url) и список: название, url, переключатель
 * «Активен/Неактивен» (PUT …/enabled — при активации бэкенд подключается и
 * заполняет tools), сворачиваемый список инструментов активных серверов
 * (нативный <details>, по умолчанию закрыт; чипы: имя в чипе, описание — в title)
 * и кнопка удаления.
 * Ошибки бэкенда ({error: "…"}) показываются инлайном, без alert.
 */
export default function McpServersPanel({ disabled }: McpServersPanelProps) {
  const [servers, setServers] = useState<McpServer[] | null>(null);
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [addBusy, setAddBusy] = useState(false);
  /** id сервера, над которым сейчас идёт мутация (toggle/delete) — блокируем только его строку. */
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Ошибка API показывается «коротко»: гасим через ERROR_VISIBLE_MS (вместо alert).
  useEffect(() => {
    if (error == null) return;
    const timer = window.setTimeout(() => setError(null), ERROR_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [error]);

  /** Перечитать каталог серверов; ошибки загрузки не забивают интерфейс — старый список остаётся. */
  const refresh = () =>
    fetchMcpServers()
      .then(setServers)
      .catch(() => {
        /* сервис недоступен — список остаётся прежним (или null до первой загрузки) */
      });

  // Каталог грузим один раз при монтировании (панель самодостаточна, как ProfileSelect).
  useEffect(() => {
    let cancelled = false;
    fetchMcpServers()
      .then((list) => {
        if (!cancelled) setServers(list);
      })
      .catch(() => {
        /* сервис недоступен — пусто до следующей мутации/перезагрузки */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /** Добавление сервера: POST /api/mcp-servers; после успеха — очистка формы + перечитывание. */
  const handleAdd = async () => {
    setAddBusy(true);
    setError(null);
    try {
      await createMcpServer({ name: name.trim(), url: url.trim() });
      setName('');
      setUrl('');
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setAddBusy(false);
    }
  };

  /** Активация/деактивация: PUT …/enabled; бэкенд при включении подключается и отдаёт
   *  tools — показываем состояние «Подключение…» на строке, инструменты придут из refresh. */
  const handleToggle = async (server: McpServer) => {
    setRowBusyId(server.id);
    setError(null);
    try {
      await setMcpServerEnabled(server.id, !server.enabled);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRowBusyId(null);
    }
  };

  /** Удаление сервера: DELETE …/{id}; после успеха — перечитывание каталога. */
  const handleDelete = async (id: number) => {
    setRowBusyId(id);
    setError(null);
    try {
      await deleteMcpServer(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRowBusyId(null);
    }
  };

  return (
    // Единственная из сворачиваемых панелей, развёрнутая по умолчанию (defaultOpen).
    <CollapsibleSection
      className="llm-settings-block"
      title="MCP-серверы"
      icon="☢"
      hint="внешние инструменты агента"
      defaultOpen
    >
      {/* Список серверов: имя + url + переключатель активности + инструменты + удаление. */}
      {servers === null ? (
        <div className="mem-hint">Загрузка…</div>
      ) : servers.length === 0 ? (
        <div className="mem-empty-inline">Серверов нет</div>
      ) : (
        <div className="mcp-servers">
          {servers.map((server) => {
            const rowBusy = rowBusyId === server.id;
            const connecting = rowBusy && !server.enabled;
            return (
              <div key={server.id} className="mcp-server">
                <div className="mcp-server-top">
                  {/* Переключатель активности: зелёный «Активен» / серый «Неактивен». */}
                  <button
                    type="button"
                    role="switch"
                    aria-checked={server.enabled}
                    aria-label={`Сервер «${server.name}»: ${server.enabled ? 'активен' : 'неактивен'}`}
                    className={`llm-switch mcp-switch${server.enabled ? ' is-on' : ''}`}
                    disabled={disabled || rowBusy}
                    title={
                      server.enabled
                        ? 'Деактивировать сервер (инструменты пропадут из набора агента)'
                        : 'Активировать сервер: бэкенд подключится и прочитает список инструментов'
                    }
                    onClick={() => void handleToggle(server)}
                  >
                    <span className="llm-switch-knob" />
                  </button>
                  <span
                    className={`mcp-state${server.enabled ? ' is-on' : ''}`}
                    title={server.enabled ? 'Сервер подключён' : 'Сервер отключён'}
                  >
                    {connecting ? 'Подключение…' : server.enabled ? 'Активен' : 'Неактивен'}
                  </span>
                  <span className="mcp-server-name" title={server.name}>
                    {server.name}
                  </span>
                  <button
                    type="button"
                    className="mem-entry-del"
                    title="Удалить сервер"
                    aria-label={`Удалить сервер «${server.name}»`}
                    disabled={disabled || rowBusy}
                    onClick={() => void handleDelete(server.id)}
                  >
                    ×
                  </button>
                </div>
                <div className="mcp-server-url" title={server.url}>
                  {server.url}
                </div>
                {server.enabled && server.tools.length > 0 ? (
                  /* Инструменты активного сервера: нативный <details> (по умолчанию
                     закрыт, без React-состояния) — чип-табличка на каждый tool,
                     имя — в чипе, описание — в title (у .mcp-tool-chip cursor: help). */
                  <details className="mcp-tools-details">
                    <summary className="mcp-tools-summary" title="Список инструментов сервера">
                      Инструменты ({server.tools.length})
                    </summary>
                    <div className="mcp-tools">
                      {server.tools.map((tool) => (
                        <span key={tool.name} className="mcp-tool-chip" title={tool.description}>
                          {tool.name}
                        </span>
                      ))}
                    </div>
                  </details>
                ) : null}
              </div>
            );
          })}
        </div>
      )}

      {/* Форма добавления сервера: имя + url + кнопка. */}
      <form
        className="mem-form"
        onSubmit={(e: FormEvent<HTMLFormElement>) => {
          e.preventDefault();
          void handleAdd();
        }}
      >
        {error != null ? (
          <div className="mem-error" role="alert">
            {error}
          </div>
        ) : null}
        <input
          className="mem-input"
          placeholder="Имя сервера"
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={disabled || addBusy}
          aria-label="Имя MCP-сервера"
          maxLength={80}
        />
        <input
          className="mem-input"
          placeholder="http://localhost:8787/mcp"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          disabled={disabled || addBusy}
          aria-label="URL MCP-сервера"
          maxLength={300}
        />
        <button
          type="submit"
          className="mem-btn mem-btn-primary"
          disabled={name.trim() === '' || url.trim() === '' || addBusy || disabled}
          title="Добавить сервер: POST /api/mcp-servers (создаётся неактивным)"
        >
          {addBusy ? 'Сохранение…' : 'Добавить'}
        </button>
      </form>
    </CollapsibleSection>
  );
}
