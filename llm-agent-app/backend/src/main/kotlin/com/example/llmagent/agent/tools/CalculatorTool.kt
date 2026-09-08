package com.example.llmagent.agent.tools

import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

/**
 * Безопасный калькулятор арифметических выражений.
 * Парсит только числа, + - * / и скобки — без eval произвольного кода.
 */
@Component
class CalculatorTool : Tool {

    override val name = "calculator"
    override val description =
        "Безопасное вычисление арифметического выражения. " +
            "Принимает JSON-аргумент {\"expression\": \"<выражение>\"}. " +
            "Поддерживаются числа, + - * / и круглые скобки. " +
            "Пример выражения: \"(2 + 3) * 4 / 2\"."

    override val parameters: JsonNode = run {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        val props = root.putObject("properties")
        val expr = props.putObject("expression")
        expr.put("type", "string")
        expr.put("description", "Арифметическое выражение, например \"2+2\"")
        root.putArray("required").add("expression")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val expr = (args["expression"] as? String)?.trim().orEmpty()
        if (expr.isEmpty()) return ToolResult("Аргумент 'expression' (string) обязателен", true)
        return try {
            ToolResult(formatNumber(Parser(expr).parse()))
        } catch (e: Exception) {
            ToolResult("Ошибка вычисления: ${e.message}", true)
        }
    }

    private fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** Рекурсивный спуск: expr -> term -> factor -> number/скобки. */
    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skipWs()
            if (pos < src.length) {
                throw IllegalArgumentException("Недопустимый символ '${src[pos]}' на позиции $pos")
            }
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { pos++; v *= factor() }
                    '/' -> {
                        pos++
                        val d = factor()
                        if (d == 0.0) throw IllegalArgumentException("Деление на ноль")
                        v /= d
                    }
                    else -> return v
                }
            }
        }

        private fun factor(): Double {
            skipWs()
            when (peek()) {
                '-' -> { pos++; return -factor() }
                '+' -> { pos++; return factor() }
                '(' -> {
                    pos++
                    val v = expr()
                    skipWs()
                    if (peek() != ')') throw IllegalArgumentException("Ожидалась закрывающая скобка ')'")
                    pos++
                    return v
                }
                else -> return number()
            }
        }

        private fun number(): Double {
            skipWs()
            val start = pos
            var sawDot = false
            while (pos < src.length) {
                val ch = src[pos]
                when {
                    ch.isDigit() -> pos++
                    ch == '.' && !sawDot -> { sawDot = true; pos++ }
                    else -> break
                }
            }
            if (pos == start) throw IllegalArgumentException("Ожидалось число на позиции $start")
            return src.substring(start, pos).toDouble()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
    }
}
