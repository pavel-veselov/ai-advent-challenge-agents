package com.example.llmagent.agent.tools

import com.example.llmagent.agent.TaskStateStore
import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

/**
 * Объявление инструмента `task_state` для LLM (Day-13): агент обновляет формализованное
 * состояние задачи — этап конечного автомата, текущий шаг и ожидаемое действие.
 *
 * ИСПОЛНЕНИЕ — НЕ ЗДЕСЬ: инструменты не получают sessionId, поэтому агентский цикл
 * перехватывает вызовы по имени (AgentImpl.handleTaskState) и пишет состояние через
 * TaskStateStore с реальным sessionId сессии. [execute] достижим только при ошибке
 * конфигурации (инструмент, зарегистрированный, но не перехваченный агентом) и честно
 * об этом сообщает.
 *
 * Инструмент НЕ трогает флаг паузы: paused сохраняется прежним (новая строка создаётся
 * с paused=0); паузу/продолжение делает только пользователь через REST/UI.
 */
@Component
class TaskStateTool : Tool {

    companion object {
        /** Имя инструмента — константа для перехвата вызова в AgentImpl (см. handleTaskState). */
        const val TOOL_NAME = "task_state"
    }

    override val name = TOOL_NAME

    override val description =
        "Обновляет состояние текущей задачи (конечный автомат). " +
            "Аргументы JSON: {\"stage\": \"planning|execution|validation|done\" (обязательно), " +
            "\"current_step\": \"...\" (опционально), \"expected_action\": \"...\" (опционально)}. " +
            "Вызывай после значимых продвижений: смена этапа, уточнение текущего шага или " +
            "ожидаемого действия. Переходы СТРОГО ЛИНЕЙНЫЕ — перепрыгивать этап и откатываться " +
            "назад нельзя: planning→execution, execution→validation, validation→done; done — " +
            "терминальный (дальше переходов нет); тот же этап можно обновить в любой момент."

    override val parameters: JsonNode = run {
        val root: ObjectNode = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        val props = root.putObject("properties")
        val stage = props.putObject("stage")
        stage.put("type", "string")
        stage.put(
            "enum",
            JsonNodeFactory.instance.arrayNode().apply {
                add(TaskStateStore.STAGE_PLANNING)
                add(TaskStateStore.STAGE_EXECUTION)
                add(TaskStateStore.STAGE_VALIDATION)
                add(TaskStateStore.STAGE_DONE)
            },
        )
        stage.put("description", "Этап задачи: planning | execution | validation | done")
        val step = props.putObject("current_step")
        step.put("type", "string")
        step.put("description", "Текущий шаг задачи (что делается сейчас), кратко")
        val action = props.putObject("expected_action")
        action.put("type", "string")
        action.put("description", "Ожидаемое следующее действие или результат текущего шага")
        root.putArray("required").add("stage")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        // Нормальный путь — перехват в AgentImpl.handleTaskState (нужен sessionId сессии).
        return ToolResult(
            "Инструмент task_state должен исполняться агентским циклом (перехват по имени); " +
                "прямой вызов не поддержан",
            true,
        )
    }
}
