package com.example.rag.extract

import com.example.rag.config.CorpusConfig
import com.example.rag.model.Document
import java.io.File

/**
 * Собирает манифест корпуса: список [Document] по [CorpusConfig].
 *
 * - md/code-файлы читает [Utf8TextExtractor];
 * - все *.pdf из каталога [CorpusConfig.pdfDir] — [PdfExtractor];
 * - отсутствующие файлы пропускаются с предупреждением (не роняют сборку корпуса).
 */
class CorpusManifest(private val config: CorpusConfig) {

	/** Построить список документов корпуса. */
	fun build(): List<Document> {
		val utf8 = Utf8TextExtractor()
		val pdfExtractor = PdfExtractor()
		val docs = mutableListOf<Document>()

		for (rel in config.mdFiles + config.codeFiles) {
			val file = config.moduleDir.resolve(rel).normalize().toFile()
			if (!file.isFile) {
				println("[WARN] Пропущен (файл не найден): $rel")
				continue
			}
			docs += utf8.extract(file)
		}

		val pdfDir = config.pdfDir.toFile()
		if (!pdfDir.isDirectory) {
			println("[WARN] Каталог PDF не найден: ${config.pdfDir}")
		} else {
			val pdfs = pdfDir.listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
				?.sortedBy { it.name }
				?: emptyList()
			if (pdfs.isEmpty()) println("[WARN] В каталоге ${config.pdfDir} нет *.pdf файлов")
			for (pdf in pdfs) docs += pdfExtractor.extract(pdf)
		}

		return docs
	}
}
