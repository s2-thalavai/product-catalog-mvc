# syntax=docker/dockerfile:1.7
# Multi-stage build: compile with Maven, run on a small JRE image as a non-root user.
#   docker build -t product-catalog-mvc:local .

############################ build ############################
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src

# Resolve dependencies in their own layer so code changes don't re-download them.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline

COPY src ./src
# Tests run in CI (mvn verify) before the image is built; skip them here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q package -DskipTests

############################ runtime ############################
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG APP_UID=10001
RUN addgroup -S -g ${APP_UID} app && adduser -S -D -H -u ${APP_UID} -G app app

WORKDIR /app
COPY --from=build --chown=${APP_UID}:${APP_UID} /src/target/product-catalog-mvc.jar /app/app.jar

# Container-aware heap; crash fast on OOM so Kubernetes restarts the pod.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    APP_PORT=8080

USER ${APP_UID}
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1

# exec so the JVM is PID 1 and receives SIGTERM directly (graceful shutdown).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

LABEL org.opencontainers.image.title="product-catalog-mvc" \
      org.opencontainers.image.description="Product Catalog REST API (Spring Boot / Spring MVC)"
