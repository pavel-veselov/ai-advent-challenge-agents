import java.nio.file.Paths

plugins {
	id("org.springframework.boot") version "3.5.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("jvm") version "2.1.20"
	kotlin("plugin.spring") version "2.1.20"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

// Если каталог проекта содержит не-ASCII символы (кириллицу), сборка идёт в
// ASCII-каталог в system temp: форкируемый тестовый JVM использует кодировку
// имён файлов ОС (sun.jnu.encoding = cp1251 на русской Windows, не перекрывается
// через -D), поэтому загрузка классов из кириллического пути падает с CNFE.
val projectPathNonAscii = projectDir.absolutePath.any { it.code > 127 }
if (projectPathNonAscii) {
    layout.buildDirectory.set(
        Paths.get(System.getProperty("java.io.tmpdir"), "llm-agent-build")
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
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.xerial:sqlite-jdbc:3.53.4.0")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
