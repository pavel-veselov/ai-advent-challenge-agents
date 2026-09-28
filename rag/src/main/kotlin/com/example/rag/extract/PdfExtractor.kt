package com.example.rag.extract

import com.example.rag.model.DocType
import com.example.rag.model.Document
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Извлекатель текста из PDF через Apache PDFBox (2.0.x: PDDocument.load).
 * Страницы склеиваются маркерами вида \n\n[PAGE n]\n\n — структурный
 * чанкер (следующий шаг) сможет резать документ по страницам.
 */
class PdfExtractor {

	companion object {
		/** Маркер начала страницы n (n >= 2; первая страница маркера не получает). */
		fun pageMarker(n: Int): String = "\n\n[PAGE $n]\n\n"
	}

	/** Извлечь весь текст PDF постранично, title = имя файла без расширения. */
	fun extract(file: File): Document {
		PDDocument.load(file).use { pdf ->
			val sb = StringBuilder()
			for (page in 1..pdf.numberOfPages) {
				if (page > 1) sb.append(pageMarker(page))
				val stripper = PDFTextStripper()
				stripper.startPage = page
				stripper.endPage = page
				sb.append(stripper.getText(pdf).trim())
			}
			return Document(
				path = file.absolutePath,
				type = DocType.PDF,
				title = file.nameWithoutExtension,
				rawText = sb.toString(),
			)
		}
	}
}
