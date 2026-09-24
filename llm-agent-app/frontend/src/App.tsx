import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import ChatPanel from './components/ChatPanel';
import InvariantsPanel from './components/InvariantsPanel';
import LlmSettings from './components/LlmSettings';
import McpServersPanel from './components/McpServersPanel';
import ProfileSelect from './components/ProfileSelect';
import SchedulerPanel from './components/SchedulerPanel';
import StepsLog from './components/StepsLog';
import TabBar from './components/TabBar';
import WorkflowSettings from './components/WorkflowSettings';
import { fetchActiveProfile, fetchInvariants, fetchProfiles, fetchProjectMemory } from './api';
import { useAgentSession } from './hooks/useAgentSession';
import type { Invariant, MemoryState, Profile } from './types';

/** Дефолтная ширина правой колонки — лог шагов (steps-panel) (px). */
const DEFAULT_STEPS_WIDTH = 320;
/** Ширина левой колонки панелей (профиль · настройки LLM · воркфлоу · инварианты · MCP) — px. */
const PANELS_WIDTH = 320;
/** Минимальная ширина правой колонки (px). */
const MIN_STEPS_WIDTH = 200;
/** Максимум ширины колонки — доля ширины окна (чтобы чат не схлопывался). */
const MAX_STEPS_WIDTH_RATIO = 0.55;
/** Верхняя граница при восстановлении ширины из localStorage (точный max считает drag). */
const MAX_STEPS_WIDTH_PX = 1200;
/** localStorage-ключ ширины правой колонки: переживает перезагрузку страницы. */
const STEPS_WIDTH_KEY = 'llm-agent-steps-width';

/** Читает сохранённую ширину правой колонки; мусор/недоступность — дефолт. */
function readPersistedStepsWidth(): number {
  try {
    const raw = localStorage.getItem(STEPS_WIDTH_KEY);
    if (raw == null) return DEFAULT_STEPS_WIDTH;
    const parsed = Number.parseInt(raw, 10);
    if (!Number.isFinite(parsed)) return DEFAULT_STEPS_WIDTH;
    return Math.min(Math.max(parsed, MIN_STEPS_WIDTH), MAX_STEPS_WIDTH_PX);
  } catch {
    return DEFAULT_STEPS_WIDTH;
  }
}

export default function App() {
  const session = useAgentSession();
  // Форма «+ Проект»: инлайн-ввод имени вместо window.prompt — не блокирует вкладку.
  const [projectFormOpen, setProjectFormOpen] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  // Ширина правой колонки (настройки + лог): меняется разделителем, переживает перезагрузку.
  const [stepsWidth, setStepsWidth] = useState<number>(readPersistedStepsWidth);
  const [resizing, setResizing] = useState(false);
  /** Оболочка приложения (app-shell): источник ширины для ограничения «максимум 55%». */
  const appShellRef = useRef<HTMLDivElement | null>(null);
  /** Стартовое состояние перетаскивания: x курсора + ширина колонки на момент pointerdown. */
  const resizeStartRef = useRef<{ x: number; width: number } | null>(null);
  /** Зеркало stepsWidth для финального сохранения (защита от устаревшего замыкания). */
  const stepsWidthRef = useRef(stepsWidth);

  // Снапшот памяти активного проекта (GET /api/projects/{id}/memory): список долговременной
  // памяти (глобальной) виден в блоке настроек LLM; после очистки кнопкой «Очистить
  // долговременную память» снапшот перечитывается — longTerm пуст.
  const [memory, setMemory] = useState<MemoryState | null>(null);
  const activeProjectId = session.activeProjectId;

  useEffect(() => {
    if (activeProjectId == null) {
      setMemory(null);
      return;
    }
    let cancelled = false;
    fetchProjectMemory(activeProjectId)
      .then((m) => {
        if (!cancelled) setMemory(m);
      })
      .catch(() => {
        /* сервис недоступен — снапшот остаётся прежним */
      });
    return () => {
      cancelled = true;
    };
  }, [activeProjectId]);

  /** После успешной очистки LTM: перечитать снапшот памяти (longTerm теперь пуст). */
  const handleMemoryCleared = () => {
    if (activeProjectId == null) return;
    fetchProjectMemory(activeProjectId).then(setMemory).catch(() => {});
  };

  // Инварианты активного проекта (GET /api/projects/{id}/invariants): обязательные
  // ограничения ассистента, общие для всех сессий проекта. Список перечитывается после
  // любой мутации (add/delete) через handleInvariantsRefreshed — REST-мутации бэкенд
  // SSE-событием не сопровождает.
  const [invariants, setInvariants] = useState<Invariant[] | null>(null);

  useEffect(() => {
    if (activeProjectId == null) {
      setInvariants(null);
      return;
    }
    let cancelled = false;
    fetchInvariants(activeProjectId)
      .then((list) => {
        if (!cancelled) setInvariants(list);
      })
      .catch(() => {
        /* сервис недоступен — список остаётся прежним */
      });
    return () => {
      cancelled = true;
    };
  }, [activeProjectId]);

  /** Перечитать инварианты проекта после мутации (add/delete). */
  const handleInvariantsRefreshed = (projectId: number): Promise<void> =>
    fetchInvariants(projectId).then(setInvariants).catch(() => {});

  // Профили пользователя (персонализация агента): глобальный справочник + глобальный
  // активный профиль (вне проектов/сессий). Грузим один раз при монтировании; после
  // создания/удаления/смены в ProfileSelect перечитываем через handleProfilesRefresh.
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [activeProfileId, setActiveProfileId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchProfiles()
      .then((list) => {
        if (!cancelled) setProfiles(list);
      })
      .catch(() => {
        /* сервис недоступен — справочник остаётся пустым до перечитывания */
      });
    fetchActiveProfile()
      .then((a) => {
        if (!cancelled) setActiveProfileId(a.activeProfileId);
      })
      .catch(() => {
        /* сервис недоступен — остаёмся на «Без профиля» */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /** Перечитать справочник и активный профиль после CRUD в ProfileSelect. */
  const handleProfilesRefresh = () => {
    fetchProfiles()
      .then(setProfiles)
      .catch(() => {
        /* перечитывание фоновое — прежний список остаётся до следующей попытки */
      });
    fetchActiveProfile()
      .then((a) => setActiveProfileId(a.activeProfileId))
      .catch(() => {
        /* перечитывание фоновое — прежнее значение остаётся */
      });
  };

  // Вкладки блокируем только при неработающем/запускающемся бэкенде: переключение и
  // создание сессий разрешены всегда, даже пока какая-то сессия стримит.
  const tabDisabled = session.backendStopped || session.backendStarting;
  // Настройки LLM: блокируем при неработающем бэкенде ИЛИ пока активная сессия «думает»
  // (менять настройки посреди выполнения собственного запуска сомнительно).
  const llmSettingsDisabled = tabDisabled || session.isRunning;

  // Воркфлоу Day-14: «Продолжить»/«Отмена» под последним сообщением ассистента показываем,
  // только когда воркфлоу включён, режим ручной И агент ждёт подтверждения перехода.
  const workflowEnabled = session.workflowSettings?.enabled ?? false;
  const workflowMode = session.workflowSettings?.mode ?? 'manual';
  const workflowPending =
    workflowEnabled && workflowMode === 'manual' && session.taskState?.awaitConfirmation === true;
  // Кнопка паузы/снятия паузы на полосе состояния — только в авто-режиме (ручной
  // подтверждает переход кнопками в чате, снимать паузу не нужно).
  const showPause = workflowEnabled && workflowMode === 'auto';

  const handleCreateProject = () => {
    const name = newProjectName.trim();
    if (name === '') return;
    void session.createProject(name).then(() => {
      setNewProjectName('');
      setProjectFormOpen(false);
    });
  };

  const handleDeleteProject = (id: number, name: string) => {
    // Каскад на бэкенде стирает сессии проекта и его рабочую память (LTM остаётся) —
    // такое удаление подтверждаем явно.
    if (window.confirm(`Удалить проект «${name}»? Сессии проекта и его рабочая память будут удалены.`)) {
      void session.deleteProject(id);
    }
  };

  // ----- Горизонтальное масштабирование колонки лога (перетаскивание разделителя) -----
  const handleResizeStart = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.preventDefault();
    resizeStartRef.current = { x: e.clientX, width: stepsWidthRef.current };
    setResizing(true);
    // Захват указателя: move/up продолжают приходить разделителю даже за его пределами.
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const handleResizeMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    const start = resizeStartRef.current;
    if (start == null) return;
    const container = appShellRef.current;
    const max = container != null ? container.clientWidth * MAX_STEPS_WIDTH_RATIO : MAX_STEPS_WIDTH_PX;
    // Колонка справа: тянем разделитель влево — колонка шире.
    const next = start.width + (start.x - e.clientX);
    const clamped = Math.min(Math.max(next, MIN_STEPS_WIDTH), Math.max(MIN_STEPS_WIDTH, max));
    stepsWidthRef.current = clamped;
    setStepsWidth(clamped);
  };

  const handleResizeEnd = () => {
    if (resizeStartRef.current == null) return;
    resizeStartRef.current = null;
    setResizing(false);
    try {
      localStorage.setItem(STEPS_WIDTH_KEY, String(stepsWidthRef.current));
    } catch {
      /* localStorage недоступен — ширина живёт только в памяти */
    }
  };

  // Сохранение сообщения из чата в память: guard на пустой проект/сессию — кнопки и так
  // заблокированы, здесь страховка на уровне обработчиков (идиома App для void-promise).
  const handleSaveWorkingNote = (note: string): Promise<void> => {
    const pid = session.activeProjectId;
    return pid == null ? Promise.resolve() : session.saveWorkingNote(pid, note);
  };
  const handleSaveLongTerm = (note: string): Promise<void> => {
    const sid = session.sessionId;
    return sid == null ? Promise.resolve() : session.saveLongTerm(sid, note);
  };

  // Левая колонка панелей (sidebar-column): профиль, настройки LLM, воркфлоу, инварианты,
  // MCP-серверы. Стоит левее рабочей зоны (проект-бар → вкладки → чат); ширина фиксированная
  // (PANELS_WIDTH). Лог шагов переехал в правую колонку (stepsArea) — на бывшее место
  // sidebar-column.
  const panelsArea = (
    <div className="sidebar-column" style={{ width: PANELS_WIDTH }}>
      {/* Профиль пользователя (персонализация агента) — глобальный. */}
      <ProfileSelect
        profiles={profiles}
        activeProfileId={activeProfileId}
        onRefresh={handleProfilesRefresh}
      />
      <LlmSettings
        settings={session.runSettings}
        sessionId={session.sessionId}
        disabled={llmSettingsDisabled}
        strategy={session.strategy}
        windowSize={session.windowSize}
        facts={session.facts}
        onChangeStrategy={(patch) => {
          if (session.sessionId == null) return Promise.resolve();
          return session.changeContextStrategy(session.sessionId, patch);
        }}
        memory={memory}
        onMemoryCleared={handleMemoryCleared}
      />
      {/* Воркфлоу Day-14: переключатель «Следовать воркфлоу» + режим ручное/авто. */}
      <WorkflowSettings
        settings={session.workflowSettings}
        onChange={session.changeWorkflowSettings}
        disabled={tabDisabled}
      />
      {/* Инварианты Day-14: обязательные ограничения ассистента проекта (рядом с памятью). */}
      <InvariantsPanel
        projectId={activeProjectId ?? 0}
        invariants={invariants}
        refreshInvariants={handleInvariantsRefreshed}
        disabled={tabDisabled || activeProjectId == null}
      />
      {/* MCP-серверы Day-16: управление внешними источниками инструментов (глобально). */}
      <McpServersPanel disabled={tabDisabled} />
      {/* Планировщик Day-17: периодические задачи сбора данных + сводка (глобально). */}
      <SchedulerPanel disabled={tabDisabled} />
    </div>
  );

  // Правая колонка лога шагов (steps-panel, «Этапы задачи» + журнал агента): бывшее место
  // sidebar-column. Разделитель + колонка — прямые дети app-shell: колонка тянется на всю
  // высоту окна и видна всегда — даже до создания проекта/сессии.
  const stepsArea = (
    <>
      <div
        className={`steps-resizer${resizing ? ' active' : ''}`}
        title="Потяните, чтобы изменить ширину колонки лога"
        onPointerDown={handleResizeStart}
        onPointerMove={handleResizeMove}
        onPointerUp={handleResizeEnd}
        onPointerCancel={handleResizeEnd}
      />
      <div className="steps-column" style={{ width: stepsWidth }}>
        {/* Состояние задачи (Day-13 FSM) живёт в панели чата — полоса над журналом. */}
        <StepsLog
          steps={session.steps}
          backendStopped={session.backendStopped}
          backendStarting={session.backendStarting}
          onStop={session.stopService}
          onStart={session.startService}
        />
      </div>
    </>
  );

  // Нет ни одного проекта — весь экран отдаём под «Создать проект» (чистый старт day12:
  // старые сессии без проекта удалены, чат начинается с проекта). Колонки панелей и лога видны и здесь.
  if (session.projects.length === 0) {
    return (
      <div className="app-shell" ref={appShellRef}>
        {panelsArea}
        <div className="app-left">
          <div className="app-main">
            <div className="app-empty">
              <p className="app-empty-title">Проектов пока нет</p>
              <p className="app-empty-hint">
                Проект — контейнер сессий: у проекта общая рабочая память, а долговременная
                живёт глобально. Создайте первый проект — внутри него появятся сессии.
              </p>
              <form
                className="project-create-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  handleCreateProject();
                }}
              >
                <input
                  className="project-create-input"
                  placeholder="Название проекта"
                  value={newProjectName}
                  onChange={(e) => setNewProjectName(e.target.value)}
                  aria-label="Название проекта"
                  maxLength={80}
                  autoFocus
                />
                <button
                  type="submit"
                  className="app-empty-btn"
                  disabled={tabDisabled || newProjectName.trim() === ''}
                >
                  Создать проект
                </button>
              </form>
            </div>
          </div>
        </div>
        {stepsArea}
      </div>
    );
  }

  return (
    <div className="app-shell" ref={appShellRef}>
      {/* Слева — колонка панелей (panelsArea: sidebar-column). Правее — рабочая зона (app-left):
          проект-бар → вкладки → чат. Крайняя правая — колонка лога шагов (stepsArea), бывшее
          место sidebar-column: тянется на всю высоту окна, ширина меняется разделителем. */}
      {panelsArea}
      <div className="app-left">
      {/* Уровень 1: проекты. Активный подсвечен, ✕ удаляет (каскад на бэкенде). */}
      <div className="project-bar">
        {session.projects.map((p) => (
          <div
            key={p.id}
            className={`project-chip${p.id === session.activeProjectId ? ' active' : ''}`}
          >
            <button
              type="button"
              className="project-chip-name"
              title={p.name}
              onClick={() => session.selectProject(p.id)}
              disabled={tabDisabled}
            >
              {p.name}
            </button>
            <button
              type="button"
              className="project-chip-del"
              title="Удалить проект (сессии и рабочая память проекта будут удалены)"
              onClick={() => handleDeleteProject(p.id, p.name)}
              disabled={tabDisabled}
            >
              ×
            </button>
          </div>
        ))}
        {projectFormOpen ? (
          <form
            className="project-create-form"
            onSubmit={(e) => {
              e.preventDefault();
              handleCreateProject();
            }}
          >
            <input
              className="project-create-input"
              placeholder="Название проекта"
              value={newProjectName}
              onChange={(e) => setNewProjectName(e.target.value)}
              aria-label="Название проекта"
              maxLength={80}
              autoFocus
            />
            <button
              type="submit"
              className="project-btn"
              disabled={tabDisabled || newProjectName.trim() === ''}
            >
              Создать
            </button>
            <button
              type="button"
              className="project-btn"
              onClick={() => {
                setProjectFormOpen(false);
                setNewProjectName('');
              }}
            >
              Отмена
            </button>
          </form>
        ) : (
          <button
            type="button"
            className="project-add"
            title="Новый проект"
            onClick={() => setProjectFormOpen(true)}
            disabled={tabDisabled}
          >
            + Проект
          </button>
        )}
      </div>
      {/* Уровень 2: вкладки сессий активного проекта (существующий TabBar). */}
      {session.activeProjectId != null ? (
        <TabBar
          tabs={session.tabs}
          activeId={session.activeId}
          titles={session.titles}
          disabled={tabDisabled}
          onSelect={session.switchSession}
          onClose={session.closeSession}
          onNew={session.newSession}
        />
      ) : null}
      {/* Уровень 3: рабочая область — чат (app-main); левее — колонка панелей, правее —
          колонка лога шагов (обе — уровни app-shell: видны всегда). */}
      <div className="app-main">
          {session.activeProjectId == null || session.tabs.length === 0 ? (
            <div className="app-empty">
              <p className="app-empty-title">Сессий пока нет</p>
              <p className="app-empty-hint">
                Создайте сессию в проекте — чат станет доступен, а рабочая память проекта
                будет общей для всех его сессий. Настройки LLM можно менять сразу,
                до первого сообщения.
              </p>
              <button
                type="button"
                className="app-empty-btn"
                onClick={session.newSession}
                disabled={tabDisabled || session.activeProjectId == null}
              >
                Создать сессию
              </button>
            </div>
          ) : (
            <ChatPanel
              messages={session.messages}
              isRunning={session.isRunning}
              backendStopped={session.backendStopped}
              backendStarting={session.backendStarting}
              error={session.error}
              sessionId={session.sessionId}
              activeProjectId={session.activeProjectId}
              strategy={session.strategy}
              branches={session.branches}
              workflowPending={workflowPending}
              onWorkflowContinue={() => {
                if (session.sessionId != null) session.continueWorkflow(session.sessionId);
              }}
              onWorkflowCancel={() => {
                if (session.sessionId != null) void session.cancelWorkflow(session.sessionId);
              }}
              onSend={session.sendMessage}
              onStop={session.stopAgent}
              onDeleteSession={session.deleteSession}
              onSaveWorkingNote={handleSaveWorkingNote}
              onSaveLongTerm={handleSaveLongTerm}
              onForkBranch={(messageId) => {
                if (session.sessionId != null) void session.forkBranch(session.sessionId, messageId);
              }}
              onSwitchBranch={(branchId) => {
                if (session.sessionId != null) void session.switchBranch(session.sessionId, branchId);
              }}
              taskState={session.taskState}
              taskDisabled={tabDisabled}
              onChangePaused={(paused) =>
                session.sessionId == null
                  ? Promise.reject(new Error('Нет активной сессии'))
                  : session.changeTaskState(session.sessionId, { paused })
              }
              workflowEnabled={workflowEnabled}
              showPause={showPause}
            />
           )}
        </div>
      </div>
      {stepsArea}
    </div>
  );
}
