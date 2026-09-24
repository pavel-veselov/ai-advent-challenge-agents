package com.example.mcpserver.scheduler

import java.nio.file.Path
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Вспомогательный код для юнит-тестов SQLite-хранилища планировщика: поднимает
 * JdbcTemplate поверх файла БД (как в backend SqliteTestSupport).
 */
object SqliteTestSupport {

    /** JdbcTemplate поверх SQLite-файла dbFile (прямые слэши, чтобы Windows корректно распарсил путь). */
    fun jdbc(dbFile: Path): JdbcTemplate {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return JdbcTemplate(ds)
    }
}
