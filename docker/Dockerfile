# ─────────────────────────────────────────────────
# Argument: which Maven module to build
# Usage: docker build --build-arg SERVICE=agent-orchestrator -t agent-orchestrator:latest .
# ─────────────────────────────────────────────────
ARG SERVICE=agent-orchestrator

# ── Stage 1: Build ────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

ARG SERVICE
WORKDIR /build

# Copy parent POM and all module POMs first (layer cache)
COPY pom.xml .
COPY common-lib/pom.xml        common-lib/pom.xml
COPY market-data-service/pom.xml  market-data-service/pom.xml
COPY scheduler-service/pom.xml    scheduler-service/pom.xml
COPY agent-orchestrator/pom.xml   agent-orchestrator/pom.xml
COPY analysis-engine/pom.xml      analysis-engine/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY history-service/pom.xml      history-service/pom.xml
COPY trade-service/pom.xml        trade-service/pom.xml

# Download dependencies (cached unless POMs change)
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Copy source code
COPY common-lib/src         common-lib/src
COPY market-data-service/src  market-data-service/src
COPY scheduler-service/src    scheduler-service/src
COPY agent-orchestrator/src   agent-orchestrator/src
COPY analysis-engine/src      analysis-engine/src
COPY notification-service/src notification-service/src
COPY history-service/src     history-service/src
COPY trade-service/src        trade-service/src

# Build only the target service and its dependencies
RUN ./mvnw -pl ${SERVICE} -am package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG SERVICE
WORKDIR /app

# Security: non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/${SERVICE}/target/*.jar app.jar

# JVM tuning for containers
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
