package com.example.rag.model

/**
 * Тип документа корпуса: markdown-доки, исходники (код) и PDF-статьи.
 */
enum class DocType { MD, CODE, PDF }

/**
 * Документ корпуса: путь к файлу, тип, заголовок и извлечённый текст.
 *
 * @param path    путь к исходному файлу (абсолютный, для трассировки чанков)
 * @param type    тип документа (MD / CODE / PDF)
 * @param title   короткое имя: имя файла без расширения
 * @param rawText полный извлечённый текст документа
 */
data class Document(
	val path: String,
	val type: DocType,
	val title: String,
	val rawText: String,
)
