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

# 폰트 설치 (Pretendard, Noto Sans KR) — rendering.md 3절 [기본값]
# GitHub 릴리스 페이지에서 버전 고정된 파일 다운로드
RUN mkdir -p /tmp/fonts && \
    # Pretendard v1.3.9 (OFL, orioncactus/pretendard) — zip 실물 구조 확인: public/static/*.otf, LICENSE.txt
    curl -L -o /tmp/fonts/pretendard.zip "https://github.com/orioncactus/pretendard/releases/download/v1.3.9/Pretendard-1.3.9.zip" && \
    unzip -q /tmp/fonts/pretendard.zip -d /tmp/fonts/pretendard && \
    find /tmp/fonts/pretendard/public/static -maxdepth 1 -name "*.otf" -exec cp {} /usr/share/fonts/opentype/ \; && \
    cp /tmp/fonts/pretendard/LICENSE.txt /usr/share/fonts/opentype/Pretendard-LICENSE.txt && \
    # Noto Sans KR v2.004 (OFL, notofonts/noto-cjk 릴리스 태그 Sans2.004, 자산명 17_NotoSansKR.zip) — zip 실물 구조 확인: *.otf, LICENSE
    curl -L -o /tmp/fonts/noto-sans-kr.zip "https://github.com/notofonts/noto-cjk/releases/download/Sans2.004/17_NotoSansKR.zip" && \
    unzip -q /tmp/fonts/noto-sans-kr.zip -d /tmp/fonts/noto-sans-kr && \
    cp /tmp/fonts/noto-sans-kr/*.otf /usr/share/fonts/opentype/ && \
    cp /tmp/fonts/noto-sans-kr/LICENSE /usr/share/fonts/opentype/NotoSansKR-LICENSE.txt && \
    # 폰트 캐시 갱신
    fc-cache -f /usr/share/fonts/opentype && \
    # 임시 파일 정리
    rm -rf /tmp/fonts

USER pwuser

# Health check: curl은 이 베이스 이미지에 이미 있다 [확인됨: docker run ... which curl]
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
