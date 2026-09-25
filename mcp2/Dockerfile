# ---- Build stage ----
# Uses the official Gradle image with JDK 21; the wrapper version (8.14.3) matches.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle bootJar --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# bootJar is named app.jar (see build.gradle.kts), so this path is stable.
COPY --from=build /home/gradle/src/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
