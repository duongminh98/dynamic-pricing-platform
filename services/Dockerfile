# syntax=docker/dockerfile:1.7
# Multi-stage build for a Spring Boot service (R16.1 one-command bring-up).
# Pass --build-arg SERVICE=<name> (e.g. customer-service). The bootJar is built
# INSIDE Docker so a clean `docker compose up --build` needs no host-side Gradle
# step. A BuildKit cache mount on the Gradle home is shared across all service
# builds, so dependencies are downloaded once and rebuilds stay fast/small.
FROM eclipse-temurin:17-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY services ./services
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew --no-daemon :services:${SERVICE}:bootJar

FROM eclipse-temurin:17-jre AS runtime
ARG SERVICE
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --uid 10001 appuser
COPY --from=build /workspace/services/${SERVICE}/build/libs/*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
