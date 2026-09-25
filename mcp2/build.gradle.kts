import java.nio.file.Paths

plugins {
	id("org.springframework.boot") version "3.5.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("jvm") version "2.1.20"
	kotlin("plugin.spring") version "2.1.20"
}

group = "com.example"
version = "0.1.0"

// Если каталог проекта содержит не-ASCII символы (кириллицу), сборка идёт в
// ASCII-каталог в system temp: форкируемый тестовый JVM использует кодировку
// имён файлов ОС (sun.jnu.encoding = cp1251 на русской Windows, не перекрывается
// через -D), поэтому загрузка классов из кириллического пути падает с CNFE.
val projectPathNonAscii = projectDir.absolutePath.any { it.code > 127 }
if (projectPathNonAscii) {
    layout.buildDirectory.set(
        Paths.get(System.getProperty("java.io.tmpdir"), "mcp2-build")
            .resolve(project.name)
            .toFile()
    )
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	archiveFileName.set("app.jar")
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring AI BOM — единая версия для всех модулей Spring AI / MCP.
	// ВАЖНО: версия 2.0.x завязана на Jackson 3 (tools.jackson) и НЕСОВМЕСТИМА с
	// Spring Boot 3.5.x (Jackson 2). Для Spring Boot 3.5.x нужна линия 1.1.x (Jackson 2).
	implementation(platform("org.springframework.ai:spring-ai-bom:1.1.8"))

	// Spring Boot 3.5 WebFlux (реактивный стек, Netty, Jackson).
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	// MCP-сервер по транспорту Streamable HTTP на WebFlux (официальный Java SDK 2.0.1).
	// Транзитивно тянет: spring-boot-starter-webflux, spring-ai-mcp,
	// spring-ai-mcp-annotations (@McpTool/@McpToolParam), mcp-spring-webflux,
	// и пакет io.modelcontextprotocol.sdk:mcp. НЕ пинним mcp вручную —
	// стартер сам тянет нужный вариант jackson (2.x, под Spring Boot).
	implementation("org.springframework.ai:spring-ai-starter-mcp-server-webflux")

	// Jackson + Kotlin-модуль: парсинг ответов внешних API в data-классы.
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Workspace path contains Cyrillic; without an explicit UTF-8 filesystem encoding
	// the forked test JVM mangles classpath entries and tests fail to load (CNFE).
	jvmArgs("-Dsun.jnu.encoding=UTF-8", "-Dfile.encoding=UTF-8")
	testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
