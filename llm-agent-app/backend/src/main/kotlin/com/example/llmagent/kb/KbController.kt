package com.example.llmagent.kb

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * REST баз знаний (Day-22) — без SSE: после мутаций фронтенд сам делает refetch.
 * Контракт JSON зафиксирован планом дня 22 (менять форму нельзя):
 *   GET    /api/kb             — {"knowledgeBases":[{id,name,status,active,strategy,
 *                                 chunkSize,overlap,embeddingModel,documentsCount,
 *                                 chunksCount,progress{...}|null,error,createdAt}]}
 *   POST   /api/kb             — multipart (name, strategy, chunkSize?, overlap?,
 *                                 embeddingModel?, files*): валидация → 400 с
 *                                 человекочитаемым message; успех → 201 + DTO.
 *   PUT    /api/kb/{id}/active — {"active":bool} → {"id","active"}; 404 — нет базы.
 *   DELETE /api/kb/{id}        — {"deleted":true}; 404; каскад: чанки/документы БД +
 *                                 каталог data/kb/<id>/ + остановка индексации.
 *   GET    /api/kb/models      — {"models":[{id,dimension,description}]}.
 *
 * POST — multipart читается реактивно (@RequestPart); блокирующее сохранение файлов
 * (FilePart.transferTo) вынесено в createBlocking на Schedulers.boundedElastic().
 */
@RestController
@RequestMapping("/api/kb")
class KbController(
    private val repo: KbRepository,
    private val indexer: KbIndexer,
) {

    private val log = LoggerFactory.getLogger(KbController::class.java)

    /** Прогресс индексации (только для status=indexing, иначе null). */
    data class KbProgressDto(
        val processedDocs: Int,
        val totalDocs: Int,
        val percent: Int,
        val etaSeconds: Long?,
    )

    data class KbDto(
        val id: Long,
        val name: String,
        val status: String,
        val active: Boolean,
        val strategy: String,
        val chunkSize: Int?,
        val overlap: Int?,
        val embeddingModel: String,
        val documentsCount: Int,
        val chunksCount: Int?,
        val progress: KbProgressDto?,
        val error: String?,
        val createdAt: String,
    )

    data class KnowledgeBasesResponse(val knowledgeBases: List<KbDto>)
    data class ModelsResponse(val models: List<KbModelCatalog.EmbeddingModel>)
    data class ActiveRequest(val active: Boolean? = null)
    data class ActiveResponse(val id: Long, val active: Boolean)
    data class DeleteResponse(val deleted: Boolean)
    data class ErrorResponse(val error: String)

    @GetMapping
    fun list(): KnowledgeBasesResponse = KnowledgeBasesResponse(repo.list().map { toDto(it) })

    @GetMapping("/models")
    fun models(): ModelsResponse = ModelsResponse(KbModelCatalog.models)

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun create(
        @RequestPart("name", required = false) name: String?,
        @RequestPart("strategy", required = false) strategy: String?,
        @RequestPart("chunkSize", required = false) chunkSize: String?,
        @RequestPart("overlap", required = false) overlap: String?,
        @RequestPart("embeddingModel", required = false) embeddingModel: String?,
        @RequestPart("files", required = false) files: Flux<FilePart>? = null,
    ): Mono<ResponseEntity<Any>> {
        val trimmedName = name?.trim()
        if (trimmedName.isNullOrEmpty()) return Mono.just(badRequest("name не должен быть пустым"))
        if (strategy == null || strategy !in setOf(STRATEGY_FIXED, STRATEGY_STRUCTURAL)) {
            return Mono.just(badRequest("strategy: ожидалось «fixed» или «structural», получено «$strategy»"))
        }
        val chunkSizeValue = chunkSize?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (chunkSize != null && chunkSize.trim().isNotEmpty() && (chunkSizeValue == null || chunkSizeValue <= 0)) {
            return Mono.just(badRequest("chunkSize должен быть положительным числом"))
        }
        val overlapValue = overlap?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (overlap != null && overlap.trim().isNotEmpty() && (overlapValue == null || overlapValue <= 0)) {
            return Mono.just(badRequest("overlap должен быть положительным числом"))
        }
        val model = if (embeddingModel.isNullOrBlank()) {
            KbModelCatalog.default
        } else {
            KbModelCatalog.byId(embeddingModel)
                ?: return Mono.just(
                    badRequest(
                        "Неизвестная модель эмбеддингов «$embeddingModel»: доступные — " +
                            KbModelCatalog.models.joinToString(", ") { it.id },
                    ),
                )
        }
        return Mono.fromCallable { createBlocking(trimmedName, strategy, chunkSizeValue, overlapValue, model.id, files) }
            .subscribeOn(Schedulers.boundedElastic())
    }

    /**
     * Блокирующее сохранение базы и файлов — ТОЛЬКО на boundedElastic (никогда на event loop).
     * Пустой файл ловится пост-проверкой после transferTo (FilePart не имеет isEmpty):
     * полная очистка — записанные файлы (включая пустой) + запись БД, ответ 400.
     */
    private fun createBlocking(
        name: String,
        strategy: String,
        chunkSize: Int?,
        overlap: Int?,
        embeddingModel: String,
        files: Flux<FilePart>?,
    ): ResponseEntity<Any> {
        val fileList = files?.collectList()?.block() ?: emptyList()
        if (fileList.isEmpty()) return badRequest("Загрузите хотя бы один файл")
        for (file in fileList) {
            val filename = file.filename()
            val ext = filename.substringAfterLast('.', "").lowercase()
            if (ext !in ALLOWED_EXTENSIONS) {
                return badRequest(
                    "Неподдерживаемое расширение файла «$filename»: ожидаются " +
                        ".md, .txt, .pdf, .kt, .ts, .js, .json или .csv",
                )
            }
        }
        val kb = repo.create(name, strategy, chunkSize, overlap, embeddingModel)
            ?: return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Не удалось сохранить базу знаний"))

        // Файлы — на диск data/kb/<kbId>/ (санитизация имени: только fileName).
        val dir: Path = Paths.get("data", "kb", kb.id.toString())
        val written = mutableListOf<Path>()
        return try {
            Files.createDirectories(dir)
            for (file in fileList) {
                val safeName = Paths.get(file.filename()).fileName.toString()
                val target = dir.resolve(safeName)
                file.transferTo(target).block() // легально: выполняется на boundedElastic
                written.add(target)
                if (Files.size(target) == 0L) {
                    return emptyFileCleanup(kb.id, written, safeName)
                }
                repo.addDocument(kb.id, safeName, Files.size(target))
            }
            repo.updateProgress(kb.id, 0, fileList.size, null)
            indexer.submit(kb.id, dir)
            ResponseEntity.status(HttpStatus.CREATED).body(toDto(repo.findById(kb.id) ?: kb))
        } catch (e: Exception) {
            log.warn("[KB] сохранение файлов базы #{} не удалось: {}", kb.id, e.message)
            repo.setStatusFailed(kb.id, "Не удалось сохранить файлы: ${e.message}")
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Не удалось сохранить файлы базы знаний: ${e.message}"))
        }
    }

    /** Откат при пустом файле: удалить записанные файлы (включая пустой) + запись БД, 400. */
    private fun emptyFileCleanup(kbId: Long, written: List<Path>, filename: String): ResponseEntity<Any> {
        for (path in written) {
            try {
                Files.deleteIfExists(path)
            } catch (e: Exception) {
                log.warn("[KB] очистка файлов базы #{} не удалась: {}", kbId, e.message)
            }
        }
        repo.delete(kbId)
        return badRequest("Файл «$filename» пуст")
    }

    @PutMapping("/{id}/active")
    fun setActive(
        @PathVariable id: Long,
        @RequestBody body: ActiveRequest,
    ): ResponseEntity<Any> {
        val active = body.active
            ?: return badRequest("active: ожидалось true/false")
        val updated = repo.setActive(id, active)
            ?: return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("База знаний не найдена: $id"))
        return ResponseEntity.ok(ActiveResponse(updated.id, updated.active))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Any> {
        repo.findById(id)
            ?: return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("База знаний не найдена: $id"))
        // Останавливаем идущую индексацию ДО чистки (джоба молча завершится).
        indexer.cancel(id)
        deleteFilesQuietly(id)
        return if (repo.delete(id)) {
            ResponseEntity.ok(DeleteResponse(true))
        } else {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Не удалось удалить базу знаний: $id"))
        }
    }

    /** Каталог файлов базы data/kb/<id>/ — рекурсивно, ошибки не валим (warn + продолжаем). */
    private fun deleteFilesQuietly(kbId: Long) {
        val dir = Paths.get("data", "kb", kbId.toString())
        if (!Files.exists(dir)) return
        try {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (e: Exception) {
            log.warn("[KB] не удалось удалить каталог файлов базы #{}: {}", kbId, e.message)
        }
    }

    private fun toDto(kb: KnowledgeBase): KbDto = KbDto(
        id = kb.id,
        name = kb.name,
        status = kb.status,
        active = kb.active,
        strategy = kb.strategy,
        chunkSize = kb.chunkSize,
        overlap = kb.overlap,
        embeddingModel = kb.embeddingModel,
        documentsCount = kb.documentsCount,
        chunksCount = kb.chunksCount,
        progress = kb.progressDto(),
        error = kb.error,
        createdAt = kb.createdAt,
    )

    /** Прогресс только у индексируемой базы; percent = round(processed/total*100). */
    private fun KnowledgeBase.progressDto(): KbProgressDto? {
        if (status != "indexing" || totalDocs <= 0) return null
        val percent = Math.round(processedDocs * 100.0 / totalDocs).toInt()
        return KbProgressDto(processedDocs, totalDocs, percent, etaSeconds)
    }

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse(message))

    companion object {
        const val STRATEGY_FIXED = "fixed"
        const val STRATEGY_STRUCTURAL = "structural"

        /** Разрешённые расширения файлов (контракт плана дня 22). */
        val ALLOWED_EXTENSIONS = setOf("md", "txt", "pdf", "kt", "ts", "js", "json", "csv")
    }
}
