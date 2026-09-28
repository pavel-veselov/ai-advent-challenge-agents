package com.example.rag.extract

import com.example.rag.model.DocType
import com.example.rag.model.Document
import java.io.File

/**
 * Извлекатель текстовых файлов (markdown и исходников) в UTF-8.
 * Тип определяется по расширению: .md -> MD, .kt/.ts/.tsx -> CODE.
 */
class Utf8TextExtractor {

	/** Извлечь документ: весь файл читается как UTF-8, title = имя файла без расширения. */
	fun extract(file: File): Document {
		val rawText = file.readText(Charsets.UTF_8)
		val ext = file.extension.lowercase()
		val type = when (ext) {
			"md" -> DocType.MD
			"kt", "ts", "tsx" -> DocType.CODE
			else -> throw IllegalArgumentException("Неподдерживаемое расширение «$ext» у файла ${file.path}: ожидаются .md, .kt, .ts или .tsx")
		}
		return Document(
			path = file.absolutePath,
			type = type,
			title = file.nameWithoutExtension,
			rawText = rawText,
		)
	}
}
