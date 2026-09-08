package com.example.llmagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.io.File

@SpringBootApplication
@ConfigurationPropertiesScan
class LlmAgentApplication

fun main(args: Array<String>) {
    // SQLite-драйвер не создаёт каталог для файла БД самостоятельно — создаём его
    // до инициализации DataSource, иначе старт упадёт с "unable to open database file".
    ensureSqliteDataDir(System.getenv("SQLITE_DB_PATH") ?: "./data/llm-agent.db")
    runApplication<LlmAgentApplication>(*args)
}

/** Создаёт каталог, в котором лежит файл SQLite-БД, если он ещё не существует. */
private fun ensureSqliteDataDir(dbPath: String) {
    val path = dbPath.removePrefix("file:").removePrefix("///")
    val parent = File(path).absoluteFile.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }
}
