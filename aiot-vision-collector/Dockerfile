# =============================================
# aiot-vision-collector Dockerfile
# Multi-stage build: build with Maven + run on slim JRE
# =============================================

# ---------- Build stage ----------
FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app

# Only copy pom first to leverage Docker layer cache for dependency download
COPY pom.xml ./
# (Optional) Pre-fetch dependencies (no source yet → better cache reuse)
RUN mvn -q -e -B dependency:go-offline || true

# Now copy source
COPY src ./src

# Build (skip tests by default; override with --build-arg SKIP_TESTS=false)
ARG SKIP_TESTS=true
RUN if [ "$SKIP_TESTS" = "true" ]; then \
      mvn -q -B -DskipTests package; \
    else \
      mvn -q -B package; \
    fi && \
    cp target/aiot-vision-collector-0.0.1-SNAPSHOT.jar app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Create non-root user
RUN addgroup -S app && adduser -S app -G app

# Application directories (H2 file DB will be stored under /app/data by default)
RUN mkdir -p /app/data && chown -R app:app /app
VOLUME ["/app/data"]

# Environment variables (defaults mirror application.yml placeholders)
ENV AVC_SERVER_PORT=8080 \
    AVC_DATASOURCE_URL="jdbc:h2:file:./data/visiondb;MODE=MySQL;DB_CLOSE_DELAY=-1" \
    AVC_DATASOURCE_DRIVER=org.h2.Driver \
    AVC_DATASOURCE_USERNAME=sa \
    AVC_DATASOURCE_PASSWORD= \
    AVC_JPA_DATABASE_PLATFORM=org.hibernate.dialect.H2Dialect \
    AVC_JPA_HIBERNATE_DDL_AUTO=update \
    AVC_H2_CONSOLE_ENABLED=true \
    AVC_PREDICT_API_URL="http://localhost:50000/predict" \
    AVC_PREDICT_API_PREDICTION_LENGTH=60 \
    AVC_PREDICT_API_HISTORY_LENGTH=200 \
    AVC_DATA_API_HISTORY_LIMIT=200 \
    AVC_DATA_VIEW_LATEST_MINUTES_WINDOW=5 \
    AVC_DATA_TAG_HISTORY_DEFAULT_MINUTES=3 \
    AVC_IOTDB_HOST=10.235.229.11 \
    AVC_IOTDB_PORT=6667 \
    AVC_IOTDB_USERNAME=root \
    AVC_IOTDB_PASSWORD=root \
    AVC_LOGGING_LEVEL_APP=INFO \
    JAVA_OPTS="-Xms256m -Xmx512m"

# Copy jar from build stage
COPY --from=build /app/app.jar ./app.jar

# Expose the configured server port (default 8080)
EXPOSE 8080

USER app

# Healthcheck (basic): try to hit actuator if later enabled, else root
# (You can enable Spring Boot actuator and adjust the path accordingly.)
# HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 CMD wget -qO- http://localhost:${AVC_SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# ---- Usage Examples ----
# Build (skip tests):
#   docker build -t aiot-vision-collector:latest .
# Build (run tests):
#   docker build --build-arg SKIP_TESTS=false -t aiot-vision-collector:tested .
# Run (ephemeral):
#   docker run --rm -p 8080:8080 aiot-vision-collector:latest
# Run with persistent data volume + custom predict api:
#   docker run -d --name avc \
#      -e AVC_PREDICT_API_URL=http://predict-svc:50000/predict \
#      -v avc_data:/app/data \
#      -p 18080:8080 \
#      aiot-vision-collector:latest

