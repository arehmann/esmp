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
# Uses JDK image because OpenRewrite's JavaParser.fromJavaVersion() requires JDK tooling
# (src.zip / ct.sym) for type resolution. JRE-only images cause the parser to hang.
FROM eclipse-temurin:21-jdk-jammy AS runtime

# Create a non-root user for security
RUN useradd -m -u 1000 esmp

WORKDIR /app

# Copy layered JAR contents in change-frequency order (least-changed first for cache efficiency)
COPY --from=builder /app/build/dependency/dependencies/ ./
COPY --from=builder /app/build/dependency/spring-boot-loader/ ./
COPY --from=builder /app/build/dependency/snapshot-dependencies/ ./
COPY --from=builder /app/build/dependency/application/ ./

# Create data directory for recipe book and other runtime files (owned by esmp user)
RUN mkdir -p /app/data/migration && chown -R esmp:esmp /app/data

USER esmp

# Container-aware JVM memory sizing (UseContainerSupport is ON by default in Java 21)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
