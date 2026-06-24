# Multi-stage build for a Spring Boot service. Pass --build-arg SERVICE=<name>
# (e.g. customer-service). Produces a slim JRE image running the bootJar.
FROM eclipse-temurin:17-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY services ./services
RUN chmod +x gradlew && ./gradlew --no-daemon :services:${SERVICE}:bootJar

FROM eclipse-temurin:17-jre AS runtime
ARG SERVICE
WORKDIR /app
RUN useradd --create-home --uid 10001 appuser
COPY --from=build /workspace/services/${SERVICE}/build/libs/*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
