# Stage 1 — Build
# Uses JDK image to compile and build the application
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Copy dependency declaration files first for layer caching
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/libs.versions.toml gradle/

# Warm dependency cache layer (failure is non-fatal)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

# Copy source and build with Vaadin frontend production bundle
COPY src src
RUN ./gradlew bootJar vaadinBuildFrontend --no-daemon -x test -x spotlessCheck

# Extract layered JAR for optimal Docker layer caching
RUN mkdir -p build/dependency && \
    java -Djarmode=layertools -jar build/libs/esmp-*.jar extract --destination build/dependency

# Stage 2 — Runtime
# Uses JRE-only image for a smaller production image
FROM eclipse-temurin:21-jre-jammy AS runtime

# Install curl for HEALTHCHECK (eclipse-temurin:21-jre-jammy may not include it)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create a non-root user for security
RUN useradd -m -u 1000 esmp

WORKDIR /app

# Copy layered JAR contents in change-frequency order (least-changed first for cache efficiency)
COPY --from=builder /app/build/dependency/dependencies/ ./
COPY --from=builder /app/build/dependency/spring-boot-loader/ ./
COPY --from=builder /app/build/dependency/snapshot-dependencies/ ./
COPY --from=builder /app/build/dependency/application/ ./

USER esmp

# Container-aware JVM memory sizing (UseContainerSupport is ON by default in Java 21)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
