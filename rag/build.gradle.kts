import java.nio.file.Paths

plugins {
	kotlin("jvm") version "2.1.20"
	kotlin("plugin.serialization") version "2.1.20"
	application
}

group = "com.example"
version = "0.1.0"

// Если каталог проекта содержит не-ASCII символы (кириллицу), сборка идёт в
// ASCII-каталог в system temp: форкируемый тестовый JVM использует кодировку
// имён файлов ОС (sun.jnu.encoding = cp1251 на русской Windows, не перекрывается
// через -D), поэтому загрузка классов из кириллического пути падает с CNFE.
// Системное свойство rag.build имеет приоритет: -Drag.build=<путь> перенаправляет
// сборку в произвольный каталог (нужно для параллельных сборок модуля).
val projectPathNonAscii = projectDir.absolutePath.any { it.code > 127 }
val ragBuild = System.getProperty("rag.build")
if (ragBuild != null) {
	layout.buildDirectory.set(File(ragBuild))
} else if (projectPathNonAscii) {
	layout.buildDirectory.set(
		Paths.get(System.getProperty("java.io.tmpdir"), "rag-build")
			.resolve(project.name)
			.toFile()
	)
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Извлечение текста из PDF (corpus/pdf/*.pdf).
	implementation("org.apache.pdfbox:pdfbox:2.0.31")

	// SQLite-хранилище индекса (data/index.db) — подключено заранее, реально
	// используется начиная со следующего шага (пайплайн индексации).
	implementation("org.xerial:sqlite-jdbc:3.46.1.3")

	// JSON (экспорт индекса, сериализация чанков).
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

	testImplementation(kotlin("test"))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
	mainClass.set("com.example.rag.MainKt")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Workspace path contains Cyrillic; without an explicit UTF-8 filesystem encoding
	// the forked test JVM mangles classpath entries and tests fail to load (CNFE).
	jvmArgs("-Dsun.jnu.encoding=UTF-8", "-Dfile.encoding=UTF-8")
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}
