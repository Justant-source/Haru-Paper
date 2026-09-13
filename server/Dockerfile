# Multi-stage build for Haru-Paper server
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY build.gradle settings.gradle gradlew ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x ./gradlew && ./gradlew build -x test

# Stage 2: Runtime with Playwright
# Playwright Java 1.45.0 corresponds to Docker image v1.45.0
FROM mcr.microsoft.com/playwright/java:v1.45.0-jammy

# Don't download browsers - they're already in the image
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

WORKDIR /app

# Copy built application from builder stage, owned by the image's non-root user (pwuser, uid 1000)
# [확인됨: docker run mcr.microsoft.com/playwright/java:v1.45.0-jammy 의 /etc/passwd 실사 확인, msaaduser는 존재하지 않음]
COPY --from=builder --chown=pwuser:pwuser /build/build/libs/*.jar app.jar

# haru-files 볼륨 마운트 지점을 pwuser가 쓸 수 있게 미리 만든다
RUN mkdir -p /data/haru-files && chown -R pwuser:pwuser /data/haru-files

USER pwuser

# Health check: curl은 이 베이스 이미지에 이미 있다 [확인됨: docker run ... which curl]
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
