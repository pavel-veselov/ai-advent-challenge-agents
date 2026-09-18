package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Состояние задачи сессии (конечный автомат Day-13): этап [stage], текущий шаг,
 * ожидаемое действие, флаг паузы и поля воркфлоу Day-14 ([plan], [implementation],
 * [validation], [awaitConfirmation]). Одна строка на сессию (session_id — PRIMARY KEY);
 * строка отсутствует → задача не начата (GET 404, PUT без stage → 400).
 *
 * Воркфлоу (Day-14): при следовании workflow этап проходит по шагам
 * planning → execution → validation → done; результат каждого этапа сохраняется в
 * соответствующую колонку (plan / implementation / validation), а флаг
 * [awaitConfirmation] поднимается в РУЧНОМ режиме — пользователь подтверждает переход
 * кнопками «Продолжить»/«Отмена» (см. TaskStateController: /continue, /cancel).
 */
data class TaskState(
    val sessionId: String,
    /** Этап FSM: planning | execution | validation | done (валидация — [TaskStateStore.isValidStage]). */
    val stage: String,
    val currentStep: String?,
    val expectedAction: String?,
    /** true — задача на паузе: агент НЕ выполняет шаги задачи. */
    val paused: Boolean,
    /** Результат этапа планирования (воркфлоу Day-14). */
    val plan: String? = null,
    /** Результат этапа выполнения (воркфлоу Day-14). */
    val implementation: String? = null,
    /** Результат этапа проверки (воркфлоу Day-14). */
    val validation: String? = null,
    /** true — агент ждёт подтверждения перехода на следующий этап (воркфлоу Day-14, ручной режим). */
    val awaitConfirmation: Boolean = false,
    val updatedAt: String,
)

/**
 * Персистентность состояния задачи сессии (таблица `task_state`) + ЕДИНЫЙ источник
 * истины переходов конечного автомата. Пишется двумя путями: инструментом `task_state`
 * из агентского цикла (AgentImpl.handleTaskState) и REST-эндпоинтом
 * (TaskStateController: пауза/продолжение, /continue, /cancel и ручная правка из UI).
 * Оба пути проверяют переходы одними и теми же функциями [canTransition]/[allowedTargets].
 *
 * Схема — в schema.sql; здесь — страховочное создание для старых файлов БД (тот же
 * приём, что ProfileStore) и миграция недостающих колонок воркфлоу (ALTER TABLE ADD
 * COLUMN, тот же приём, что JdbcSessionLlmSettingsStore). Все методы fail-open: сбой
 * БД не роняет агент — warn в лог и null/false как признак «не удалось».
 */
@Component
class TaskStateStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(TaskStateStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS task_state (
                session_id          TEXT PRIMARY KEY REFERENCES chat_sessions(session_id),
                stage               TEXT NOT NULL,
                current_step        TEXT,
                expected_action     TEXT,
                paused              INTEGER NOT NULL DEFAULT 0,
                plan                TEXT,
                implementation      TEXT,
                validation          TEXT,
                await_confirmation  INTEGER NOT NULL DEFAULT 0,
                updated_at          TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        migrateColumns()
    }

    /** Для старых файлов БД: таблица уже существовала без колонок воркфлоу — добавляем их. */
    private fun migrateColumns() {
        val existing = jdbc
            .query("PRAGMA table_info(task_state)") { rs, _ -> rs.getString("name") }
            .toSet()
        WORKFLOW_COLUMNS.forEach { column ->
            if (column !in existing) {
                jdbc.execute("ALTER TABLE task_state ADD COLUMN $column TEXT")
            }
        }
        if ("await_confirmation" !in existing) {
            jdbc.execute("ALTER TABLE task_state ADD COLUMN await_confirmation INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Состояние задачи сессии; null — задача не начата (или сбой БД). */
    fun get(sessionId: String): TaskState? {
        return try {
            jdbc.query(
                """
                SELECT session_id, stage, current_step, expected_action, paused,
                       plan, implementation, validation, await_confirmation, updated_at
                FROM task_state WHERE session_id = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                sessionId,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("TaskState.get({}) не удался: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Полная запись состояния (INSERT OR REPLACE). Валидность этапа/перехода проверяет
     * ВЫЗЫВАЮЩИЙ (контроллер/агентский цикл) через [isValidStage]/[canTransition] —
     * здесь только запись. Поля воркфлоу ([plan]/[implementation]/[validation]/
     * [awaitConfirmation]) передаются опционально (дефолт null/false) — старые вызовы
     * без них просто обнуляют эти колонки. Возвращает сохранённую строку; сбой БД — null.
     */
    fun upsert(
        sessionId: String,
        stage: String,
        currentStep: String?,
        expectedAction: String?,
        paused: Boolean,
        plan: String? = null,
        implementation: String? = null,
        validation: String? = null,
        awaitConfirmation: Boolean = false,
    ): TaskState? {
        val now = OffsetDateTime.now().toString()
        return try {
            jdbc.update(
                """
                INSERT INTO task_state
                    (session_id, stage, current_step, expected_action, paused,
                     plan, implementation, validation, await_confirmation, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    stage = excluded.stage,
                    current_step = excluded.current_step,
                    expected_action = excluded.expected_action,
                    paused = excluded.paused,
                    plan = excluded.plan,
                    implementation = excluded.implementation,
                    validation = excluded.validation,
                    await_confirmation = excluded.await_confirmation,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                sessionId,
                stage,
                currentStep?.take(TEXT_MAX_LENGTH),
                expectedAction?.take(TEXT_MAX_LENGTH),
                if (paused) 1 else 0,
                plan?.take(TEXT_MAX_LENGTH),
                implementation?.take(TEXT_MAX_LENGTH),
                validation?.take(TEXT_MAX_LENGTH),
                if (awaitConfirmation) 1 else 0,
                now,
            )
            TaskState(
                sessionId,
                stage,
                currentStep?.take(TEXT_MAX_LENGTH),
                expectedAction?.take(TEXT_MAX_LENGTH),
                paused,
                plan?.take(TEXT_MAX_LENGTH),
                implementation?.take(TEXT_MAX_LENGTH),
                validation?.take(TEXT_MAX_LENGTH),
                awaitConfirmation,
                now,
            )
        } catch (e: Exception) {
            log.warn("TaskState.upsert({}) не удался: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Воркфлоу Day-14: сохраняет результат этапа ([output]) в колонку по [stage]
     * (plan / implementation / validation) и поднимает/опускает [awaitConfirmation].
     * Строку создаёт, если задачи ещё нет (первый этап планирования). Возвращает
     * обновлённое состояние; null — сбой БД или недопустимый stage.
     */
    fun setStageOutput(
        sessionId: String,
        stage: String,
        output: String,
        await: Boolean = true,
    ): TaskState? {
        if (!isValidStage(stage)) {
            log.warn("TaskState.setStageOutput({}) — недопустимый этап: {}", sessionId, stage)
            return null
        }
        val e = get(sessionId)
        val trimmed = output.take(TEXT_MAX_LENGTH)
        val plan = if (stage == STAGE_PLANNING) trimmed else e?.plan
        val implementation = if (stage == STAGE_EXECUTION) trimmed else e?.implementation
        val validation = if (stage == STAGE_VALIDATION) trimmed else e?.validation
        return upsert(
            sessionId,
            stage,
            e?.currentStep,
            e?.expectedAction,
            e?.paused ?: false,
            plan,
            implementation,
            validation,
            await,
        )
    }

    /**
     * Воркфлоу Day-14 (авто-режим): сохраняет результат завершённого этапа
     * ([completedStage]) в соответствующую колонку, НЕ меняя текущий этап FSM —
     * модель уже перешла на следующий инструментом task_state, и её переход не надо
     * откатывать (см. [setStageOutput], который перезаписывает этап). Шаг/ожидаемое
     * действие/пауза сохраняются как есть. Возвращает обновлённое состояние;
     * null — недопустимый этап, нет строки или сбой БД.
     */
    fun setStageOutputKeepStage(
        sessionId: String,
        completedStage: String,
        output: String,
    ): TaskState? {
        if (!isValidStage(completedStage)) {
            log.warn("TaskState.setStageOutputKeepStage({}) — недопустимый этап: {}", sessionId, completedStage)
            return null
        }
        val e = get(sessionId) ?: return null
        val trimmed = output.take(TEXT_MAX_LENGTH)
        val plan = if (completedStage == STAGE_PLANNING) trimmed else e.plan
        val implementation = if (completedStage == STAGE_EXECUTION) trimmed else e.implementation
        val validation = if (completedStage == STAGE_VALIDATION) trimmed else e.validation
        return upsert(
            sessionId,
            e.stage,
            e.currentStep,
            e.expectedAction,
            e.paused,
            plan,
            implementation,
            validation,
            e.awaitConfirmation,
        )
    }

    /**
     * Воркфлоу Day-14: переход на следующий линейный этап ([advanceTo]); сбрасывает
     * [awaitConfirmation] (подтверждение получено) и паузу. Текущий шаг/ожидаемое
     * действие сохраняются — новый этап сам опишет их инструментом task_state.
     * Переход валидируется [canTransition] (строго линейный конвейер: откат назад и
     * «перепрыгивание» запрещены); при недопустимом переходе — fail-open (null).
     * Возвращает обновлённое состояние; null — строки нет, недопустимый переход или
     * сбой БД.
     */
    fun advance(
        sessionId: String,
        newStage: String,
    ): TaskState? {
        val e = get(sessionId) ?: return null
        if (!canTransition(e.stage, newStage)) {
            log.warn(
                "TaskState.advance({}) — недопустимый переход {} → {} (fail-open: null)",
                sessionId, e.stage, newStage,
            )
            return null
        }
        return upsert(
            sessionId,
            newStage,
            e.currentStep,
            e.expectedAction,
            false,
            e.plan,
            e.implementation,
            e.validation,
            false,
        )
    }

    /**
     * Воркфлоу Day-14: сброс ожидания подтверждения ([awaitConfirmation] → 0),
     * этап и пауза не трогаются. Возвращает обновлённое состояние; null — строки нет.
     */
    fun clearAwait(sessionId: String): TaskState? {
        val e = get(sessionId) ?: return null
        return upsert(
            sessionId,
            e.stage,
            e.currentStep,
            e.expectedAction,
            e.paused,
            e.plan,
            e.implementation,
            e.validation,
            false,
        )
    }

    /**
     * Только флаг паузы (PUT {paused:true|false} из UI): этап/шаги не трогаются.
     * Возвращает обновлённое состояние; null — строки нет (задача не начата) или сбой БД.
     */
    fun setPaused(sessionId: String, paused: Boolean): TaskState? {
        val now = OffsetDateTime.now().toString()
        return try {
            val updated = jdbc.update(
                "UPDATE task_state SET paused = ?, updated_at = ? WHERE session_id = ?",
                if (paused) 1 else 0,
                now,
                sessionId,
            ) > 0
            if (updated) get(sessionId) else null
        } catch (e: Exception) {
            log.warn("TaskState.setPaused({}) не удался: {}", sessionId, e.message)
            null
        }
    }

    /** Удаляет состояние задачи (каскад при удалении сессии/проекта); true — была строка. */
    fun remove(sessionId: String): Boolean {
        return try {
            jdbc.update("DELETE FROM task_state WHERE session_id = ?", sessionId) > 0
        } catch (e: Exception) {
            log.warn("TaskState.remove({}) не удался: {}", sessionId, e.message)
            false
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): TaskState = TaskState(
        sessionId = rs.getString("session_id"),
        stage = rs.getString("stage"),
        currentStep = rs.getString("current_step"),
        expectedAction = rs.getString("expected_action"),
        paused = rs.getInt("paused") != 0,
        plan = rs.getString("plan"),
        implementation = rs.getString("implementation"),
        validation = rs.getString("validation"),
        awaitConfirmation = rs.getInt("await_confirmation") != 0,
        updatedAt = rs.getString("updated_at"),
    )

    companion object {
        const val STAGE_PLANNING = "planning"
        const val STAGE_EXECUTION = "execution"
        const val STAGE_VALIDATION = "validation"
        const val STAGE_DONE = "done"

        /** Все допустимые этапы FSM. */
        val STAGES: Set<String> = setOf(STAGE_PLANNING, STAGE_EXECUTION, STAGE_VALIDATION, STAGE_DONE)

        /**
         * Разрешённые переходы (единый источник истины FSM) — СТРОГО ЛИНЕЙНЫЙ конвейер
         * Day-15: planning → execution → validation → done. Откаты назад (на любой этап)
         * запрещены; done — терминальный (переходов в новые этапы нет — новая задача
         * начинается заново с planning). Обновление того же этапа всегда допустимо
         * (см. [canTransition], `from == to`).
         */
        private val ALLOWED_TRANSITIONS: Map<String, Set<String>> = mapOf(
            STAGE_PLANNING to setOf(STAGE_EXECUTION),
            STAGE_EXECUTION to setOf(STAGE_VALIDATION),
            STAGE_VALIDATION to setOf(STAGE_DONE),
            STAGE_DONE to emptySet(),
        )

        const val TEXT_MAX_LENGTH = 2000

        /** Колонки воркфлоу Day-14, добавляемые миграцией старым файлам БД. */
        private val WORKFLOW_COLUMNS: List<String> =
            listOf("plan", "implementation", "validation")

        /** Этап входит в FSM (иначе PUT/инструмент получают ошибку). */
        fun isValidStage(stage: String): Boolean = stage in STAGES

        /**
         * Переход from → to разрешён? Тот же этап обновлять можно всегда
         * (уточнение шага/действия без смены этапа); смена — только по графу переходов.
         */
        fun canTransition(from: String, to: String): Boolean =
            from == to || ALLOWED_TRANSITIONS[from]?.contains(to) == true

        /** Разрешённые ЦЕЛЕВЫЕ этапы из from (без самого from) — для текста ошибки. */
        fun allowedTargets(from: String): Set<String> = ALLOWED_TRANSITIONS[from] ?: emptySet()

        /**
         * Человекочитаемая ошибка недопустимого перехода from → to (для ToolResult и 400):
         * перечисляет допустимые целевые этапы из from; для терминального done — «переходов
         * нет». Обновление того же этапа допустимо всегда.
         */
        fun transitionErrorMessage(from: String, to: String): String {
            val targets = allowedTargets(from).sorted()
            return if (targets.isEmpty()) {
                "Недопустимый переход \"$from\" → \"$to\". Этап \"$from\" терминальный — задача выполнена, " +
                    "переходов в новые этапы нет. Для новой задачи начните с этапа \"$STAGE_PLANNING\"."
            } else {
                "Недопустимый переход \"$from\" → \"$to\". Из этапа \"$from\" разрешено: " +
                    targets.joinToString(" | ") +
                    "; тот же этап \"$from\" можно обновить в любой момент."
            }
        }

        /**
         * Человекочитаемая ошибка «первого состояния»: задача ВСЕГДА начинается с этапа
         * [STAGE_PLANNING] — нельзя стартовать с execution/validation/done (День-15).
         */
        fun firstStageErrorMessage(stage: String): String =
            "Недопустимый старт: задача начинается с этапа \"$STAGE_PLANNING\", а не \"$stage\". " +
                "Сначала обозначь план (этап planning), затем execution → validation → done. " +
                "Допустимые этапы: ${STAGES.sorted().joinToString(" | ")}."

        /**
         * Следующий этап ЛИНЕЙНОГО воркфлоу DAY-14 для /continue (planning → execution →
         * validation → done); null — задача выполнена (этап done, продолжать некуда).
         */
        fun nextWorkflowStage(stage: String): String? = when (stage) {
            STAGE_PLANNING -> STAGE_EXECUTION
            STAGE_EXECUTION -> STAGE_VALIDATION
            STAGE_VALIDATION -> STAGE_DONE
            else -> null
        }

        /** Колонка, куда воркфлоу пишет результат этапа; null — этап без колонки (done). */
        fun stageOutputColumn(stage: String): String? = when (stage) {
            STAGE_PLANNING -> "plan"
            STAGE_EXECUTION -> "implementation"
            STAGE_VALIDATION -> "validation"
            else -> null
        }
    }
}
